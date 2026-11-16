/*
 * rearhelper - root side of the Rear Screen app for the Oscal Tank 1 rear display.
 *
 * Rear display: /dev/odm_sub_lcd (SPI, st77916/gc9307 driver), protocol from
 * /system_ext/lib64/libbv_vicescreenround.so and DKSystemUI (com.blackview.miniscreen):
 *   power on  ioctl(fd, 0x2202)        power off  ioctl(fd, 0x2201)
 *   frame     ioctl(fd, 0x40012200, buf)  buf = 240*296 RGB565, big-endian
 *   backlight /sys/devices/platform/chamsion_sublcm_helper.0/chamsion_sublcm_backlight (0..16)
 * Rear touch: input device "cst0xx_input", 240x296, same orientation as the frame.
 *
 * usage:
 *   rearhelper serve      frames (W*H*2 bytes each) on stdin are sent to the display;
 *                         the rear touch is grabbed (SystemUI does not see it) and printed
 *                         on stdout as "D x y", "M x y", "U x y" lines. Exits on stdin EOF.
 *   rearhelper pad        virtual touchscreen (uinput) on the main display: reads "D x y",
 *                         "M x y", "U x y" (rear coordinates) on stdin, X mirrored because the
 *                         panel is touched from behind. A real input device, so Android scales
 *                         it to the screen, follows rotation and draws "show taps". Exits on EOF.
 *   rearhelper on | off   power + backlight
 *   rearhelper bl <0..16>
 *   rearhelper test       colour bars
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define W 240
#define H 296
#define FRAME (W * H * 2)
#define DEV "/dev/odm_sub_lcd"
#define BL "/sys/devices/platform/chamsion_sublcm_helper.0/chamsion_sublcm_backlight"
#define IOC_FRAME 0x40012200u
#define IOC_OFF 0x2201u
#define IOC_ON 0x2202u
#define IOC_BL 0x40042206u

static int lcd_ioctl(unsigned long cmd, void *arg) {
    int fd = open(DEV, O_RDWR);
    if (fd < 0) return -errno;
    int r = arg ? ioctl(fd, cmd, arg) : ioctl(fd, cmd);
    close(fd);
    return r;
}

static int backlight(int v) {
    FILE *f = fopen(BL, "w");
    if (!f) return -1;
    fprintf(f, "%d", v);
    fclose(f);
    return 0;
}

static void power(int on) {
    int v = on;
    lcd_ioctl(on ? IOC_ON : IOC_OFF, NULL);
    lcd_ioctl(IOC_BL, &v);
}

static int find_touch(void) {
    DIR *d = opendir("/dev/input");
    if (!d) return -1;
    struct dirent *e;
    int found = -1;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, "event", 5)) continue;
        char p[64], name[128] = {0};
        snprintf(p, sizeof p, "/dev/input/%s", e->d_name);
        int fd = open(p, O_RDONLY);
        if (fd < 0) continue;
        if (ioctl(fd, EVIOCGNAME(sizeof name - 1), name) >= 0 && strstr(name, "cst0xx")) {
            found = fd;
            break;
        }
        close(fd);
    }
    closedir(d);
    return found;
}

static void *touch_thread(void *unused) {
    (void)unused;
    int fd = find_touch();
    if (fd < 0) {
        printf("E no rear touch\n");
        fflush(stdout);
        return NULL;
    }
    ioctl(fd, EVIOCGRAB, 1); /* exclusive: SystemUI / InputReader do not get it */
    printf("I touch ready\n");
    fflush(stdout);
    struct input_event ev;
    int x = 0, y = 0, down = 0, was = 0;
    while (read(fd, &ev, sizeof ev) == sizeof ev) {
        if (ev.type == EV_ABS && (ev.code == ABS_MT_POSITION_X || ev.code == ABS_X)) x = ev.value;
        else if (ev.type == EV_ABS && (ev.code == ABS_MT_POSITION_Y || ev.code == ABS_Y)) y = ev.value;
        else if (ev.type == EV_KEY && ev.code == BTN_TOUCH) down = ev.value;
        else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
            if (down && !was) printf("D %d %d\n", x, y);
            else if (down) printf("M %d %d\n", x, y);
            else if (was) printf("U %d %d\n", x, y);
            was = down;
            fflush(stdout);
        }
    }
    ioctl(fd, EVIOCGRAB, 0);
    close(fd);
    return NULL;
}

/* ---- touchpad: virtual touchscreen */
#define SLOP 6 /* rear pixels a finger may wander before a tap turns into a drag */

static int ui_fd = -1;

static void emit(int type, int code, int value) {
    struct input_event e;
    memset(&e, 0, sizeof e);
    e.type = (unsigned short)type;
    e.code = (unsigned short)code;
    e.value = value;
    if (write(ui_fd, &e, sizeof e) < 0) { /* nothing sensible to do */ }
}

static void abs_setup(int code, int max) {
    struct uinput_abs_setup a;
    memset(&a, 0, sizeof a);
    a.code = (unsigned short)code;
    a.absinfo.minimum = 0;
    a.absinfo.maximum = max;
    ioctl(ui_fd, UI_ABS_SETUP, &a);
}

static int ui_create(void) {
    ui_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (ui_fd < 0) return -errno;
    ioctl(ui_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(ui_fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(ui_fd, UI_SET_EVBIT, EV_ABS);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_X);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_Y);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(ui_fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(ui_fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT); /* touchscreen, not a touchpad */
    abs_setup(ABS_X, W - 1);
    abs_setup(ABS_Y, H - 1);
    abs_setup(ABS_MT_SLOT, 0);
    abs_setup(ABS_MT_TRACKING_ID, 65535);
    abs_setup(ABS_MT_POSITION_X, W - 1);
    abs_setup(ABS_MT_POSITION_Y, H - 1);
    struct uinput_setup u;
    memset(&u, 0, sizeof u);
    u.id.bustype = BUS_VIRTUAL; /* not USB/BT: Android treats it as an internal screen */
    u.id.vendor = 0x5245;
    u.id.product = 0x4152;
    u.id.version = 1;
    snprintf(u.name, sizeof u.name, "rear_touchpad");
    if (ioctl(ui_fd, UI_DEV_SETUP, &u) < 0) return -errno;
    if (ioctl(ui_fd, UI_DEV_CREATE) < 0) return -errno;
    return 0;
}

static void pad_point(int x, int y) {
    x = W - 1 - x; /* touched from behind: mirror */
    if (x < 0) x = 0;
    if (x > W - 1) x = W - 1;
    if (y < 0) y = 0;
    if (y > H - 1) y = H - 1;
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    emit(EV_ABS, ABS_X, x);
    emit(EV_ABS, ABS_Y, y);
}

static int pad(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    int r = ui_create();
    if (r < 0) {
        printf("E uinput: %s\n", strerror(-r));
        return 1;
    }
    usleep(300000); /* let InputReader pick the device up */
    printf("I pad ready\n");
    char line[64];
    int down = 0, drag = 0, dx = 0, dy = 0, id = 0;
    while (fgets(line, sizeof line, stdin)) {
        char c;
        int x, y;
        if (sscanf(line, "%c %d %d", &c, &x, &y) != 3) continue;
        if (c == 'D' || (c == 'M' && !down)) {
            down = 1;
            drag = 0;
            dx = x;
            dy = y;
            emit(EV_ABS, ABS_MT_SLOT, 0);
            emit(EV_ABS, ABS_MT_TRACKING_ID, id++ & 0xffff);
            pad_point(x, y);
            emit(EV_KEY, BTN_TOUCH, 1);
            emit(EV_SYN, SYN_REPORT, 0);
        } else if (c == 'M') {
            if (!drag && abs(x - dx) <= SLOP && abs(y - dy) <= SLOP) continue; /* still a tap */
            drag = 1;
            pad_point(x, y);
            emit(EV_SYN, SYN_REPORT, 0);
        } else if (c == 'U' && down) {
            if (drag) pad_point(x, y);
            emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
            emit(EV_KEY, BTN_TOUCH, 0);
            emit(EV_SYN, SYN_REPORT, 0);
            down = 0;
        }
    }
    if (down) {
        emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
        emit(EV_KEY, BTN_TOUCH, 0);
        emit(EV_SYN, SYN_REPORT, 0);
    }
    ioctl(ui_fd, UI_DEV_DESTROY);
    close(ui_fd);
    return 0;
}

static int read_full(unsigned char *b, int n) {
    int got = 0;
    while (got < n) {
        ssize_t r = read(0, b + got, n - got);
        if (r <= 0) return 0;
        got += (int)r;
    }
    return 1;
}

static int serve(void) {
    static unsigned char buf[FRAME];
    pthread_t t;
    setvbuf(stdout, NULL, _IOLBF, 0);
    power(1);
    pthread_create(&t, NULL, touch_thread, NULL);
    printf("I serve ready %dx%d\n", W, H);
    fflush(stdout);
    long n = 0;
    while (read_full(buf, FRAME)) {
        /* exactly like libbv_vicescreenround.so: open / ioctl / close for every frame
           (keeping the node open makes the SPI transfer hang in this driver) */
        struct timespec a, b;
        clock_gettime(CLOCK_MONOTONIC, &a);
        int r = lcd_ioctl(IOC_FRAME, buf);
        clock_gettime(CLOCK_MONOTONIC, &b);
        n++;
        if (r < 0 || n <= 3 || n % 300 == 0) {
            long ms = (b.tv_sec - a.tv_sec) * 1000 + (b.tv_nsec - a.tv_nsec) / 1000000;
            printf("%s frame %ld: ret=%d (%s) %ld ms\n", r < 0 ? "E" : "I", n, r, r < 0 ? strerror(-r > 0 ? -r : errno) : "ok", ms);
            fflush(stdout);
        }
    }
    return 0; /* touch grab is released when the process exits */
}

static void test(void) {
    static unsigned char b[FRAME];
    static const unsigned c[8][3] = {{255, 255, 255}, {255, 255, 0}, {0, 255, 255}, {0, 255, 0},
                                     {255, 0, 255},   {255, 0, 0},   {0, 0, 255},   {0, 0, 0}};
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) {
            const unsigned *k = c[x * 8 / W];
            unsigned short s = ((k[0] >> 3) << 11) | ((k[1] >> 2) << 5) | (k[2] >> 3);
            b[2 * (y * W + x)] = s >> 8;
            b[2 * (y * W + x) + 1] = s & 255;
        }
    power(1);
    lcd_ioctl(IOC_FRAME, b);
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: rearhelper serve|pad|on|off|bl N|test\n");
        return 2;
    }
    if (!strcmp(argv[1], "serve")) return serve();
    if (!strcmp(argv[1], "pad")) return pad();
    if (!strcmp(argv[1], "on")) { power(1); return 0; }
    if (!strcmp(argv[1], "off")) { power(0); backlight(0); return 0; }
    if (!strcmp(argv[1], "bl") && argc > 2) return backlight(atoi(argv[2])) ? 1 : 0;
    if (!strcmp(argv[1], "test")) { test(); return 0; }
    return 2;
}
