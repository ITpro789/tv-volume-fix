#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <pthread.h>
#include <sys/time.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <string.h>

static void send_to_overlay(int vol, int mute) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return;

    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 150000; // 150ms timeout
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof tv);
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (const char*)&tv, sizeof tv);

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(49200);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0) {
        char msg[32];
        if (mute) {
            snprintf(msg, sizeof(msg), "MUTE\n");
        } else {
            snprintf(msg, sizeof(msg), "%d\n", vol);
        }
        write(sock, msg, strlen(msg));
    }
    close(sock);
}

static void* cec_monitor_worker(void* arg) {
    printf("CEC Monitor Worker started. Streaming HDMI logcat...\n");
    fflush(stdout);

    FILE* fp = popen("logcat -v brief -s HDMI:D", "r");
    if (!fp) {
        perror("popen logcat");
        return NULL;
    }

    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        char* p = strstr(line, "<Report Audio Status> 50:7A:");
        if (p) {
            p += 28;
            unsigned int hex_val = 0;
            if (sscanf(p, "%2x", &hex_val) == 1) {
                int vol = hex_val & 0x7F; // bits 0-6: volume (0-100)
                int mute = (hex_val & 0x80) != 0;
                printf("CEC REPORT: Soundbar Vol=%d (mute=%d)\n", vol, mute);
                fflush(stdout);
                send_to_overlay(vol, mute);
            }
        }
    }
    pclose(fp);
    return NULL;
}

// Timing parameters
#define RELEASE_DEBOUNCE_MS 60    // 60ms silence after KEY UP confirms physical release
#define MIN_PULSE_GAP_MS    320   // Minimum gap between pulses sent to Android (prevents CEC runaway)
#define HOLD_START_DELAY_MS 380   // Initial delay before hold repeats start
#define HOLD_REPEAT_STEP_MS 480   // 0.48s: strictly 1-by-1 stepping without Yamaha hardware turbo jumps

static volatile int g_active_key = 0;              // Currently active volume key (114 or 115)
static volatile int g_last_key = 115;              // Fallback for queued taps
static volatile long long g_hold_start_time = 0;   // Timestamp when current hold started
static volatile long long g_last_down_time = 0;    // Timestamp of last IR DOWN/REPEAT
static volatile long long g_last_up_time = 0;      // Timestamp of last IR UP
static volatile long long g_last_pulse_time = 0;   // Timestamp when last pulse was emitted to Android
static volatile int g_pending_release = 0;         // 1 if KEY UP seen, waiting for debounce
static volatile int g_pending_taps = 0;            // Queue of discrete taps to emit cleanly

static int g_uinput_fd = -1;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static long long now_ms() {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long long)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

static void emit_uinput(int fd, int type, int code, int val) {
    struct input_event ie;
    memset(&ie, 0, sizeof(ie));
    ie.type = type;
    ie.code = code;
    ie.value = val;
    write(fd, &ie, sizeof(ie));
}

static void emit_clean_pulse(int key, const char* reason) {
    long long now = now_ms();
    printf("[%lld ms] EMIT PULSE: %s (key=%d)\n", now, reason, key);
    fflush(stdout);

    emit_uinput(g_uinput_fd, EV_KEY, key, 1);
    emit_uinput(g_uinput_fd, EV_SYN, SYN_REPORT, 0);
    usleep(20000); // 20ms clean keypress (frees up 20ms extra wire silence)
    emit_uinput(g_uinput_fd, EV_KEY, key, 0);
    emit_uinput(g_uinput_fd, EV_SYN, SYN_REPORT, 0);
}

// Background thread handles hold-to-repeat timing and paced tap emission
void* timing_worker(void* arg) {
    while (1) {
        usleep(10000); // 10ms check
        pthread_mutex_lock(&g_lock);

        long long now = now_ms();

        // 1. Check if pending release is confirmed (60ms silence after KEY UP)
        if (g_pending_release && (now - g_last_up_time >= RELEASE_DEBOUNCE_MS)) {
            printf("[%lld ms] PHYSICAL RELEASE CONFIRMED (key=%d, held=%lld ms)\n",
                   now, g_active_key, (g_hold_start_time > 0) ? (now - g_hold_start_time) : 0);
            fflush(stdout);
            g_active_key = 0;
            g_hold_start_time = 0;
            g_pending_release = 0;
        }

        // 2. Safety timeout: if held for > 15 seconds continuously, force release
        if (g_active_key != 0 && (now - g_hold_start_time > 15000)) {
            printf("[%lld ms] SAFETY TIMEOUT (15s exceeded)\n", now);
            fflush(stdout);
            g_active_key = 0;
            g_hold_start_time = 0;
            g_pending_release = 0;
            g_pending_taps = 0;
        }

        // 3. Emit queued manual taps with at least MIN_PULSE_GAP_MS between them
        if (g_pending_taps > 0 && (now - g_last_pulse_time >= MIN_PULSE_GAP_MS)) {
            int key = (g_active_key != 0) ? g_active_key : g_last_key;
            g_pending_taps--;
            g_last_pulse_time = now;

            pthread_mutex_unlock(&g_lock);
            emit_clean_pulse(key, "QUEUED_TAP");
            pthread_mutex_lock(&g_lock);
        }
        // 4. Handle continuous hold-to-repeat (600ms interval)
        else if (g_active_key != 0 && !g_pending_release && g_pending_taps == 0) {
            long long hold_duration = now - g_hold_start_time;

            if (hold_duration >= HOLD_START_DELAY_MS) {
                if (now - g_last_pulse_time >= HOLD_REPEAT_STEP_MS) {
                    int key = g_active_key;
                    g_last_pulse_time = now;

                    pthread_mutex_unlock(&g_lock);
                    emit_clean_pulse(key, "HOLD_STEP (0.48s)");
                    pthread_mutex_lock(&g_lock);
                }
            }
        }

        pthread_mutex_unlock(&g_lock);
    }
    return NULL;
}

int create_uinput_clone(int raw_fd) {
    uint8_t key_bits[KEY_MAX/8 + 1];
    memset(key_bits, 0, sizeof(key_bits));
    ioctl(raw_fd, EVIOCGBIT(EV_KEY, sizeof(key_bits)), key_bits);

    int ufd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (ufd < 0) {
        perror("open /dev/uinput");
        return -1;
    }

    ioctl(ufd, UI_SET_EVBIT, EV_KEY);
    ioctl(ufd, UI_SET_EVBIT, EV_SYN);

    for (int k = 1; k <= KEY_MAX; k++) {
        if (k >= 0x140 && k <= 0x14f) continue; // skip stylus range
        if (key_bits[k / 8] & (1 << (k % 8))) {
            ioctl(ufd, UI_SET_KEYBIT, k);
        }
    }

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "TPV_MutilRC");
    uidev.id.bustype = BUS_HOST;
    uidev.id.vendor  = 0;
    uidev.id.product = 0;
    uidev.id.version = 0;

    if (write(ufd, &uidev, sizeof(uidev)) < 0) {
        perror("write uinput_user_dev");
        close(ufd);
        return -1;
    }

    if (ioctl(ufd, UI_DEV_CREATE) < 0) {
        perror("UI_DEV_CREATE");
        close(ufd);
        return -1;
    }

    return ufd;
}

int main() {
    int raw_fd = open("/dev/input/event1", O_RDONLY);
    if (raw_fd < 0) {
        perror("open /dev/input/event1");
        return 1;
    }

    // 1. Create virtual uinput clone
    g_uinput_fd = create_uinput_clone(raw_fd);
    if (g_uinput_fd < 0) {
        fprintf(stderr, "Failed to create uinput clone\n");
        close(raw_fd);
        return 2;
    }
    printf("uinput clone created (fd=%d). Waiting 1.5s for InputReader...\n", g_uinput_fd);
    fflush(stdout);
    sleep(2);

    // 2. Grab /dev/input/event1 exclusively
    if (ioctl(raw_fd, EVIOCGRAB, 1) < 0) {
        perror("EVIOCGRAB 1");
        close(raw_fd);
        ioctl(g_uinput_fd, UI_DEV_DESTROY);
        close(g_uinput_fd);
        return 3;
    }
    printf("EVIOCGRAB active! Shielding Android from raw IR burst storms.\n");
    fflush(stdout);

    // 3. Start timing worker and CEC monitor threads
    pthread_t th;
    pthread_create(&th, NULL, timing_worker, NULL);

    pthread_t cec_th;
    pthread_create(&cec_th, NULL, cec_monitor_worker, NULL);

    // 4. Read events from hardware
    struct input_event ev;
    while (read(raw_fd, &ev, sizeof(ev)) == sizeof(ev)) {
        // Non-volume keys: forward transparently
        if (ev.type != EV_KEY || (ev.code != KEY_VOLUMEUP && ev.code != KEY_VOLUMEDOWN)) {
            write(g_uinput_fd, &ev, sizeof(ev));
            continue;
        }

        long long now = now_ms();
        printf("[%lld ms] RAW: code=%d value=%d\n", now, ev.code, ev.value);
        fflush(stdout);

        pthread_mutex_lock(&g_lock);

        if (ev.value == 1 || ev.value == 2) { // KEY DOWN or REPEAT
            g_last_down_time = now;
            g_last_key = ev.code;

            // If we had a pending release from a brief IR glitch (< 60ms), cancel release
            if (g_pending_release && (now - g_last_up_time <= RELEASE_DEBOUNCE_MS) && (g_active_key == ev.code)) {
                g_pending_release = 0;
                // Ongoing continuous physical hold continues seamlessly
            } else if (g_active_key == ev.code && !g_pending_release) {
                // Ongoing continuous physical hold: ignore duplicate hardware packet
                // The timing_worker will emit steps at exactly 600ms intervals
            } else {
                // New distinct manual press / tap!
                printf("[%lld ms] MANUAL PRESS: key=%d\n", now, ev.code);
                fflush(stdout);

                g_active_key = ev.code;
                g_hold_start_time = now;
                g_pending_release = 0;

                // If enough time has elapsed since last pulse, emit immediately
                if (now - g_last_pulse_time >= MIN_PULSE_GAP_MS) {
                    g_last_pulse_time = now;
                    pthread_mutex_unlock(&g_lock);
                    emit_clean_pulse(ev.code, "INSTANT_TAP");
                    pthread_mutex_lock(&g_lock);
                } else {
                    // Queue tap to emit as soon as 320ms gap is satisfied (prevents CEC runaway)
                    g_pending_taps++;
                    printf("[%lld ms] QUEUED TAP (pending=%d)\n", now, g_pending_taps);
                    fflush(stdout);
                }
            }
        } else if (ev.value == 0) { // KEY UP
            if (g_active_key == ev.code) {
                g_last_up_time = now;
                g_pending_release = 1;
            }
        }

        pthread_mutex_unlock(&g_lock);
    }

    // Teardown
    ioctl(raw_fd, EVIOCGRAB, 0);
    close(raw_fd);
    ioctl(g_uinput_fd, UI_DEV_DESTROY);
    close(g_uinput_fd);
    return 0;
}
