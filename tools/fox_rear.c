/*
 * fox_rear - shows the OrangeFox logo on the OSCAL TANK 1 rear display while in recovery.
 *
 * Rear display protocol (same as the Rear Screen app's rearhelper, taken from
 * libbv_vicescreenround.so): /dev/odm_sub_lcd,
 *   power on ioctl 0x2202, off 0x2201, backlight ioctl 0x40042206 (int 0..16),
 *   frame ioctl 0x40012200 with a 240x296 RGB565 big-endian buffer.
 * The node is opened / closed for every call (keeping it open hangs the SPI driver).
 *
 *   fox_rear show <frame.rgb565> [backlight]   daemon: logo while the main screen is lit
 *   fox_rear off                               backlight 0 + power off
 */
#include <dirent.h>
#include <errno.h>
#include <linux/input.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define W 240
#define H 296
#define FRAME (W * H * 2)
#define DEV "/dev/odm_sub_lcd"
#define BL_SYSFS "/sys/devices/platform/chamsion_sublcm_helper.0/chamsion_sublcm_backlight"
#define IOC_FRAME 0x40012200u
#define IOC_OFF 0x2201u
#define IOC_ON 0x2202u
#define IOC_BL 0x40042206u
#define MAIN_BL "/sys/class/leds/lcd-backlight/brightness"

static volatile sig_atomic_t stop;

static int lcd_ioctl(unsigned long cmd, void *arg) {
    int fd = open(DEV, O_RDWR | O_CLOEXEC);
    if (fd < 0) return -errno;
    int r = arg ? ioctl(fd, cmd, arg) : ioctl(fd, cmd);
    int e = errno;
    close(fd);
    return r < 0 ? -e : r;
}

static void backlight(int v) {
    lcd_ioctl(IOC_BL, &v);
    FILE *f = fopen(BL_SYSFS, "w");
    if (f) {
        fprintf(f, "%d", v);
        fclose(f);
    }
}

/* grab the rear touch (if its driver is loaded) so nothing else gets its events */
static int grab_touch(void) {
    DIR *d = opendir("/dev/input");
    if (!d) return -1;
    struct dirent *e;
    int found = -1;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, "event", 5)) continue;
        char p[64], name[128] = {0};
        snprintf(p, sizeof p, "/dev/input/%s", e->d_name);
        int fd = open(p, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        if (ioctl(fd, EVIOCGNAME(sizeof name - 1), name) >= 0 && strstr(name, "cst0xx")) {
            if (ioctl(fd, EVIOCGRAB, 1) == 0) { found = fd; break; }
        }
        close(fd);
    }
    closedir(d);
    return found;
}

/* main panel brightness, -1 if unknown (then the rear stays on) */
static int main_brightness(void) {
    FILE *f = fopen(MAIN_BL, "r");
    if (!f) return -1;
    int v = -1;
    if (fscanf(f, "%d", &v) != 1) v = -1;
    fclose(f);
    return v;
}

static void on_term(int s) {
    (void)s;
    stop = 1;
}

int main(int argc, char **argv) {
    if (argc >= 2 && !strcmp(argv[1], "off")) {
        backlight(0);
        int r = lcd_ioctl(IOC_OFF, NULL);
        printf("off: %d\n", r);
        return r < 0;
    }
    if (argc < 3 || strcmp(argv[1], "show")) {
        fprintf(stderr, "usage: fox_rear show <frame.rgb565> [backlight 1..16] | off\n");
        return 2;
    }
    static unsigned char buf[FRAME];
    FILE *f = fopen(argv[2], "rb");
    if (!f || fread(buf, 1, FRAME, f) != FRAME) {
        fprintf(stderr, "cannot read %s (need %d bytes)\n", argv[2], FRAME);
        return 1;
    }
    fclose(f);
    int bl = argc > 3 ? atoi(argv[3]) : 10;

    signal(SIGTERM, on_term);
    signal(SIGINT, on_term);
    int tfd = grab_touch();
    printf("rear touch: %s\n", tfd >= 0 ? "grabbed" : "not present");
    /* the rear follows the main screen: dark when recovery blanks the main panel
       (screen timeout / power key), back on with the logo when it lights up again */
    int lit = 0, n = 0;
    for (int sec = 0; !stop; sec++) {
        int want = main_brightness() != 0;
        if (want && !lit) {
            int r = lcd_ioctl(IOC_ON, NULL);
            backlight(bl);
            r = lcd_ioctl(IOC_FRAME, buf);
            if (n++ < 3 || r < 0) printf("rear on, frame: %d %s\n", r, r < 0 ? strerror(-r) : "ok");
            lit = 1;
            sec = 0;
        } else if (!want && lit) {
            backlight(0);
            lcd_ioctl(IOC_OFF, NULL);
            printf("rear off (main screen dark)\n");
            lit = 0;
        } else if (lit && sec % 20 == 19) {
            int r = lcd_ioctl(IOC_FRAME, buf); /* refresh */
            if (r < 0) printf("refresh: %d %s\n", r, strerror(-r));
        }
        if (tfd < 0 && sec < 30 && sec % 5 == 4) tfd = grab_touch(); /* driver may load late */
        fflush(stdout);
        sleep(1);
    }
    backlight(0);
    lcd_ioctl(IOC_OFF, NULL);
    return 0;
}
