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
 *   rearhelper on | off   power + backlight
 *   rearhelper bl <0..16>
 *   rearhelper test       colour bars
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
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
        fprintf(stderr, "usage: rearhelper serve|on|off|bl N|test\n");
        return 2;
    }
    if (!strcmp(argv[1], "serve")) return serve();
    if (!strcmp(argv[1], "on")) { power(1); return 0; }
    if (!strcmp(argv[1], "off")) { power(0); backlight(0); return 0; }
    if (!strcmp(argv[1], "bl") && argc > 2) return backlight(atoi(argv[2])) ? 1 : 0;
    if (!strcmp(argv[1], "test")) { test(); return 0; }
    return 2;
}
