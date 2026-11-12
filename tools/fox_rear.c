/*
 * fox_rear - shows the OrangeFox logo on the OSCAL TANK 1 rear display while in recovery.
 *
 * Rear display protocol (same as the Rear Screen app's rearhelper, taken from
 * libbv_vicescreenround.so): /dev/odm_sub_lcd,
 *   power on ioctl 0x2202, off 0x2201, backlight ioctl 0x40042206 (int 0..16),
 *   frame ioctl 0x40012200 with a 240x296 RGB565 big-endian buffer.
 * The node is opened / closed for every call (keeping it open hangs the SPI driver).
 *
 *   fox_rear show <frame.rgb565> [backlight]   power on, draw, redraw every 20 s (daemon)
 *   fox_rear off                               backlight 0 + power off
 */
#include <errno.h>
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
    int r = lcd_ioctl(IOC_ON, NULL);
    printf("power on: %d\n", r);
    backlight(bl);
    for (int n = 0; !stop; n++) {
        r = lcd_ioctl(IOC_FRAME, buf);
        if (n < 2 || r < 0) {
            printf("frame %d: %d %s\n", n, r, r < 0 ? strerror(-r) : "ok");
            fflush(stdout);
        }
        for (int i = 0; i < 20 && !stop; i++) sleep(1);
    }
    backlight(0);
    lcd_ioctl(IOC_OFF, NULL);
    return 0;
}
