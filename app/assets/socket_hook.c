#define _GNU_SOURCE
#include <dlfcn.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
#include <stdarg.h>
#include <errno.h>
#include <android/log.h>

#ifndef NEW_PREFIX
#define NEW_PREFIX "/data/user/0/com.orailnoor.droiddesk/files/usr"
#endif

static const char *TERMUX_PREFIX = "/data/data/com.termux/files/usr";
static const char *REL_TERMUX_PREFIX = "data/data/com.termux/files/usr";
static const char *REL_TERMUX_BASE = "data/data/com.termux";

/* Path-based libc wrappers we need to redirect. */
static int (*real_connect)(int, const struct sockaddr *, socklen_t) = NULL;
static int (*real_open)(const char *, int, ...) = NULL;
static int (*real_open64)(const char *, int, ...) = NULL;
static int (*real_openat)(int, const char *, int, ...) = NULL;
static int (*real_openat64)(int, const char *, int, ...) = NULL;
static FILE *(*real_fopen)(const char *, const char *) = NULL;
static FILE *(*real_fopen64)(const char *, const char *) = NULL;
static int (*real_stat)(const char *, struct stat *) = NULL;
static int (*real_stat64)(const char *, struct stat64 *) = NULL;
static int (*real_lstat)(const char *, struct stat *) = NULL;
static int (*real_lstat64)(const char *, struct stat64 *) = NULL;
static int (*real_fstatat)(int, const char *, struct stat *, int) = NULL;
static int (*real_fstatat64)(int, const char *, struct stat64 *, int) = NULL;
static int (*real_access)(const char *, int) = NULL;
static int (*real_faccessat)(int, const char *, int, int) = NULL;
static int (*real_mkdir)(const char *, mode_t) = NULL;
static int (*real_mkdirat)(int, const char *, mode_t) = NULL;
static int (*real_chmod)(const char *, mode_t) = NULL;
static int (*real_fchmodat)(int, const char *, mode_t, int) = NULL;
static int (*real_chown)(const char *, uid_t, gid_t) = NULL;
static int (*real_lchown)(const char *, uid_t, gid_t) = NULL;
static int (*real_fchownat)(int, const char *, uid_t, gid_t, int) = NULL;
static int (*real_unlink)(const char *) = NULL;
static int (*real_unlinkat)(int, const char *, int) = NULL;
static int (*real_rmdir)(const char *) = NULL;
static int (*real_rename)(const char *, const char *) = NULL;
static int (*real_renameat)(int, const char *, int, const char *) = NULL;
static ssize_t (*real_readlink)(const char *, char *, size_t) = NULL;
static ssize_t (*real_readlinkat)(int, const char *, char *, size_t) = NULL;
static int (*real_symlink)(const char *, const char *) = NULL;
static int (*real_symlinkat)(const char *, int, const char *) = NULL;
static int (*real_link)(const char *, const char *) = NULL;
static int (*real_linkat)(int, const char *, int, const char *, int) = NULL;
static int (*real_utimensat)(int, const char *, const struct timespec [2], int) = NULL;
static int (*real_utimes)(const char *, const struct timeval [2]) = NULL;
static int (*real_lutimes)(const char *, const struct timeval [2]) = NULL;
static int (*real_mknod)(const char *, mode_t, dev_t) = NULL;
static int (*real_mknodat)(int, const char *, mode_t, dev_t) = NULL;
static int (*real_mkfifo)(const char *, mode_t) = NULL;
static int (*real_mkfifoat)(int, const char *, mode_t) = NULL;
static void *(*real_dlopen)(const char *, int) = NULL;

static volatile int hook_initialized = 0;
static volatile int hook_initializing = 0;

static void do_init() {
    if (hook_initialized) return;
    if (__sync_lock_test_and_set(&hook_initializing, 1)) {
        int spins = 10000;
        while (!hook_initialized && spins-- > 0) {}
        return;
    }
    real_connect = dlsym(RTLD_NEXT, "connect");
    real_open = dlsym(RTLD_NEXT, "open");
    real_open64 = dlsym(RTLD_NEXT, "open64");
    real_openat = dlsym(RTLD_NEXT, "openat");
    real_openat64 = dlsym(RTLD_NEXT, "openat64");
    real_fopen = dlsym(RTLD_NEXT, "fopen");
    real_fopen64 = dlsym(RTLD_NEXT, "fopen64");
    real_stat = dlsym(RTLD_NEXT, "stat");
    real_stat64 = dlsym(RTLD_NEXT, "stat64");
    real_lstat = dlsym(RTLD_NEXT, "lstat");
    real_lstat64 = dlsym(RTLD_NEXT, "lstat64");
    real_fstatat = dlsym(RTLD_NEXT, "fstatat");
    real_fstatat64 = dlsym(RTLD_NEXT, "fstatat64");
    real_access = dlsym(RTLD_NEXT, "access");
    real_faccessat = dlsym(RTLD_NEXT, "faccessat");
    real_mkdir = dlsym(RTLD_NEXT, "mkdir");
    real_mkdirat = dlsym(RTLD_NEXT, "mkdirat");
    real_chmod = dlsym(RTLD_NEXT, "chmod");
    real_fchmodat = dlsym(RTLD_NEXT, "fchmodat");
    real_chown = dlsym(RTLD_NEXT, "chown");
    real_lchown = dlsym(RTLD_NEXT, "lchown");
    real_fchownat = dlsym(RTLD_NEXT, "fchownat");
    real_unlink = dlsym(RTLD_NEXT, "unlink");
    real_unlinkat = dlsym(RTLD_NEXT, "unlinkat");
    real_rmdir = dlsym(RTLD_NEXT, "rmdir");
    real_rename = dlsym(RTLD_NEXT, "rename");
    real_renameat = dlsym(RTLD_NEXT, "renameat");
    real_readlink = dlsym(RTLD_NEXT, "readlink");
    real_readlinkat = dlsym(RTLD_NEXT, "readlinkat");
    real_symlink = dlsym(RTLD_NEXT, "symlink");
    real_symlinkat = dlsym(RTLD_NEXT, "symlinkat");
    real_link = dlsym(RTLD_NEXT, "link");
    real_linkat = dlsym(RTLD_NEXT, "linkat");
    real_utimensat = dlsym(RTLD_NEXT, "utimensat");
    real_utimes = dlsym(RTLD_NEXT, "utimes");
    real_lutimes = dlsym(RTLD_NEXT, "lutimes");
    real_mknod = dlsym(RTLD_NEXT, "mknod");
    real_mknodat = dlsym(RTLD_NEXT, "mknodat");
    real_mkfifo = dlsym(RTLD_NEXT, "mkfifo");
    real_mkfifoat = dlsym(RTLD_NEXT, "mkfifoat");
    real_dlopen = dlsym(RTLD_NEXT, "dlopen");
    __sync_synchronize();
    hook_initialized = 1;
    __sync_lock_release(&hook_initializing);
}

__attribute__((constructor))
static void init_hook() {
    do_init();
}

static void setup() {
    if (!hook_initialized) do_init();
}

static const char* strip_dot_slash(const char *path) {
    if (path && path[0] == '.' && path[1] == '/') {
        return path + 2;
    }
    return path;
}

static const char* rewrite_path(const char* path, char* buf, size_t buf_size) {
    if (!path) return path;

    const char *p = strip_dot_slash(path);

    size_t rel_len = strlen(REL_TERMUX_PREFIX);
    if (strncmp(p, REL_TERMUX_PREFIX, rel_len) == 0) {
        char next = p[rel_len];
        if (next == '/' || next == '\0') {
            snprintf(buf, buf_size, "%s%s", NEW_PREFIX, p + rel_len);
            return buf;
        }
    }

    size_t base_len = strlen(REL_TERMUX_BASE);
    if (strncmp(p, REL_TERMUX_BASE, base_len) == 0) {
        char next = p[base_len];
        if (next == '/' || next == '\0') {
            snprintf(buf, buf_size, "%s", NEW_PREFIX);
            return buf;
        }
    }

    size_t term_len = strlen(TERMUX_PREFIX);
    if (strncmp(path, TERMUX_PREFIX, term_len) == 0) {
        snprintf(buf, buf_size, "%s%s", NEW_PREFIX, path + term_len);
        return buf;
    }

    size_t abs_base_len = strlen("/data/data/com.termux");
    if (strncmp(path, "/data/data/com.termux", abs_base_len) == 0) {
        char next = path[abs_base_len];
        if (next == '/' || next == '\0') {
            snprintf(buf, buf_size, "%s", NEW_PREFIX);
            return buf;
        }
    }

    const char *tmpdir = getenv("TMPDIR");
    const char *fhs_prefixes[] = {"/usr/", "/bin/", "/lib/", "/etc/", "/var/", "/tmp/"};
    const char *subdirs[] = {"usr", "bin", "lib", "etc", "var", "tmp"};
    for (int i = 0; i < 6; i++) {
        size_t len = strlen(fhs_prefixes[i]);
        if (strncmp(path, fhs_prefixes[i], len) == 0) {
            if (i == 5 && tmpdir) {
                snprintf(buf, buf_size, "%s/%s", tmpdir, path + len);
            } else {
                snprintf(buf, buf_size, "%s/%s%s", NEW_PREFIX, subdirs[i], path + len);
            }
            return buf;
        }
    }

    return path;
}

/* Helper: rewrite a path if dirfd refers to the current working directory. */
static const char* rewrite_at_path(int dirfd, const char* path, char* buf, size_t buf_size) {
    if (dirfd == AT_FDCWD) {
        return rewrite_path(path, buf, buf_size);
    }
    return path;
}

int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    setup();
    if (addr && addr->sa_family == AF_UNIX) {
        struct sockaddr_un un;
        memset(&un, 0, sizeof(un));
        memcpy(&un, addr, addrlen > sizeof(un) ? sizeof(un) : addrlen);

        const char *termux_target = "/data/data/com.termux/files/usr/tmp/.X11-unix/X0";
        const char *tmp_target = "/tmp/.X11-unix/X0";
        const char *new_prefix_tmp = getenv("TMPDIR");
        if (!new_prefix_tmp) new_prefix_tmp = NEW_PREFIX;
        /* Stack-local, not static: connect() may run on multiple threads and a
         * shared static buffer would race and corrupt the rewritten path. */
        char path_buf[256];
        const char *new_path = NULL;

        if (strncmp(un.sun_path, termux_target, strlen(termux_target)) == 0) {
            snprintf(path_buf, sizeof(path_buf), "%s/.X11-unix/X0", new_prefix_tmp);
            new_path = path_buf;
        } else if (strncmp(un.sun_path, tmp_target, strlen(tmp_target)) == 0) {
            snprintf(path_buf, sizeof(path_buf), "%s/.X11-unix/X0", new_prefix_tmp);
            new_path = path_buf;
        }
        if (new_path) {
            strncpy(un.sun_path, new_path, sizeof(un.sun_path) - 1);
            un.sun_path[sizeof(un.sun_path) - 1] = '\0';
            socklen_t new_len = offsetof(struct sockaddr_un, sun_path) + strlen(un.sun_path) + 1;
            return real_connect(sockfd, (struct sockaddr *)&un, new_len);
        }
    }
    return real_connect(sockfd, addr, addrlen);
}

int open(const char *pathname, int flags, ...) {
    setup();
    char buf[1024];
    pathname = rewrite_path(pathname, buf, sizeof(buf));
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, int);
        va_end(args);
        return real_open(pathname, flags, mode);
    }
    return real_open(pathname, flags);
}

int openat(int dirfd, const char *pathname, int flags, ...) {
    setup();
    char buf[1024];
    pathname = rewrite_at_path(dirfd, pathname, buf, sizeof(buf));
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, int);
        va_end(args);
        return real_openat(dirfd, pathname, flags, mode);
    }
    return real_openat(dirfd, pathname, flags);
}

FILE *fopen(const char *pathname, const char *mode) {
    setup();
    char buf[1024];
    return real_fopen(rewrite_path(pathname, buf, sizeof(buf)), mode);
}

int stat(const char *pathname, struct stat *statbuf) {
    setup();
    char buf[1024];
    return real_stat(rewrite_path(pathname, buf, sizeof(buf)), statbuf);
}

int lstat(const char *pathname, struct stat *statbuf) {
    setup();
    char buf[1024];
    return real_lstat(rewrite_path(pathname, buf, sizeof(buf)), statbuf);
}

int open64(const char *pathname, int flags, ...) {
    setup();
    char buf[1024];
    pathname = rewrite_path(pathname, buf, sizeof(buf));
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, int);
        va_end(args);
        if (real_open64) return real_open64(pathname, flags, mode);
        return real_open(pathname, flags, mode);
    }
    if (real_open64) return real_open64(pathname, flags);
    return real_open(pathname, flags);
}

int openat64(int dirfd, const char *pathname, int flags, ...) {
    setup();
    char buf[1024];
    pathname = rewrite_at_path(dirfd, pathname, buf, sizeof(buf));
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, int);
        va_end(args);
        if (real_openat64) return real_openat64(dirfd, pathname, flags, mode);
        return real_openat(dirfd, pathname, flags, mode);
    }
    if (real_openat64) return real_openat64(dirfd, pathname, flags);
    return real_openat(dirfd, pathname, flags);
}

FILE *fopen64(const char *pathname, const char *mode) {
    setup();
    char buf[1024];
    if (real_fopen64) return real_fopen64(rewrite_path(pathname, buf, sizeof(buf)), mode);
    return real_fopen(rewrite_path(pathname, buf, sizeof(buf)), mode);
}

int stat64(const char *pathname, struct stat64 *statbuf) {
    setup();
    char buf[1024];
    if (real_stat64) return real_stat64(rewrite_path(pathname, buf, sizeof(buf)), statbuf);
    return real_stat(rewrite_path(pathname, buf, sizeof(buf)), (struct stat*)statbuf);
}

int lstat64(const char *pathname, struct stat64 *statbuf) {
    setup();
    char buf[1024];
    if (real_lstat64) return real_lstat64(rewrite_path(pathname, buf, sizeof(buf)), statbuf);
    return real_lstat(rewrite_path(pathname, buf, sizeof(buf)), (struct stat*)statbuf);
}

int fstatat(int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    setup();
    char buf[1024];
    return real_fstatat(dirfd, rewrite_at_path(dirfd, pathname, buf, sizeof(buf)), statbuf, flags);
}

int fstatat64(int dirfd, const char *pathname, struct stat64 *statbuf, int flags) {
    setup();
    char buf[1024];
    if (real_fstatat64) return real_fstatat64(dirfd, rewrite_at_path(dirfd, pathname, buf, sizeof(buf)), statbuf, flags);
    return real_fstatat(dirfd, rewrite_at_path(dirfd, pathname, buf, sizeof(buf)), (struct stat*)statbuf, flags);
}

int access(const char *pathname, int mode) {
    setup();
    char buf[1024];
    return real_access(rewrite_path(pathname, buf, sizeof(buf)), mode);
}

int faccessat(int dirfd, const char *pathname, int mode, int flags) {
    setup();
    char buf[1024];
    return real_faccessat(dirfd, rewrite_at_path(dirfd, pathname, buf, sizeof(buf)), mode, flags);
}

int mkdir(const char *pathname, mode_t mode) {
    setup();
    char buf[1024];
    return real_mkdir(rewrite_path(pathname, buf, sizeof(buf)), mode);
}

int mkdirat(int dirfd, const char *pathname, mode_t mode) {
    setup();
    char buf[1024];
    return real_mkdirat(dirfd, rewrite_at_path(dirfd, pathname, buf, sizeof(buf)), mode);
}

int chmod(const char *pathname, mode_t mode) {
    setup();
    char buf[1024];
    return real_chmod(rewrite_path(pathname, buf, sizeof(buf)), mode);
}

int fchmodat(int dirfd, const char *pathname, mode_t mode, int flags) {
    setup();
    char buf[1024];
    return real_fchmodat(dirfd, rewrite_at_path(dirfd, pathname, buf, sizeof(buf)), mode, flags);
}

int chown(const char *pathname, uid_t owner, gid_t group) {
    setup();
    char buf[1024];
    return real_chown(rewrite_path(pathname, buf, sizeof(buf)), owner, group);
}

int lchown(const char *pathname, uid_t owner, gid_t group) {
    setup();
    char buf[1024];
    return real_lchown(rewrite_path(pathname, buf, sizeof(buf)), owner, group);
}

int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    setup();
    char buf[1024];
    return real_fchownat(dirfd, rewrite_at_path(dirfd, pathname, buf, sizeof(buf)), owner, group, flags);
}

int unlink(const char *pathname) {
    setup();
    char buf[1024];
    return real_unlink(rewrite_path(pathname, buf, sizeof(buf)));
}

int unlinkat(int dirfd, const char *pathname, int flags) {
    setup();
    char buf[1024];
    return real_unlinkat(dirfd, rewrite_at_path(dirfd, pathname, buf, sizeof(buf)), flags);
}

int rmdir(const char *pathname) {
    setup();
    char buf[1024];
    return real_rmdir(rewrite_path(pathname, buf, sizeof(buf)));
}

int rename(const char *oldpath, const char *newpath) {
    setup();
    char oldbuf[1024], newbuf[1024];
    return real_rename(rewrite_path(oldpath, oldbuf, sizeof(oldbuf)),
                       rewrite_path(newpath, newbuf, sizeof(newbuf)));
}

int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    setup();
    char oldbuf[1024], newbuf[1024];
    const char *rold = (olddirfd == AT_FDCWD) ? rewrite_path(oldpath, oldbuf, sizeof(oldbuf)) : oldpath;
    const char *rnew = (newdirfd == AT_FDCWD) ? rewrite_path(newpath, newbuf, sizeof(newbuf)) : newpath;
    return real_renameat(olddirfd, rold, newdirfd, rnew);
}

ssize_t readlink(const char *pathname, char *buf, size_t bufsiz) {
    setup();
    char pathbuf[1024];
    return real_readlink(rewrite_path(pathname, pathbuf, sizeof(pathbuf)), buf, bufsiz);
}

ssize_t readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    setup();
    char pathbuf[1024];
    return real_readlinkat(dirfd, rewrite_at_path(dirfd, pathname, pathbuf, sizeof(pathbuf)), buf, bufsiz);
}

int symlink(const char *target, const char *linkpath) {
    setup();
    char linkbuf[1024];
    char targetbuf[1024];
    const char *new_target = target;
    size_t term_len = strlen(TERMUX_PREFIX);
    if (target && strncmp(target, TERMUX_PREFIX, term_len) == 0) {
        snprintf(targetbuf, sizeof(targetbuf), "%s%s", NEW_PREFIX, target + term_len);
        new_target = targetbuf;
    }
    return real_symlink(new_target, rewrite_path(linkpath, linkbuf, sizeof(linkbuf)));
}

int symlinkat(const char *target, int dirfd, const char *linkpath) {
    setup();
    char linkbuf[1024];
    char targetbuf[1024];
    const char *new_target = target;
    size_t term_len = strlen(TERMUX_PREFIX);
    if (target && strncmp(target, TERMUX_PREFIX, term_len) == 0) {
        snprintf(targetbuf, sizeof(targetbuf), "%s%s", NEW_PREFIX, target + term_len);
        new_target = targetbuf;
    }
    return real_symlinkat(new_target, dirfd, rewrite_at_path(dirfd, linkpath, linkbuf, sizeof(linkbuf)));
}

int link(const char *oldpath, const char *newpath) {
    setup();
    char oldbuf[1024], newbuf[1024];
    return real_link(rewrite_path(oldpath, oldbuf, sizeof(oldbuf)),
                     rewrite_path(newpath, newbuf, sizeof(newbuf)));
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    setup();
    char oldbuf[1024], newbuf[1024];
    const char *rold = (olddirfd == AT_FDCWD) ? rewrite_path(oldpath, oldbuf, sizeof(oldbuf)) : oldpath;
    const char *rnew = (newdirfd == AT_FDCWD) ? rewrite_path(newpath, newbuf, sizeof(newbuf)) : newpath;
    return real_linkat(olddirfd, rold, newdirfd, rnew, flags);
}

int utimensat(int dirfd, const char *pathname, const struct timespec times[2], int flags) {
    setup();
    char buf[1024];
    return real_utimensat(dirfd, rewrite_at_path(dirfd, pathname, buf, sizeof(buf)), times, flags);
}

int utimes(const char *pathname, const struct timeval times[2]) {
    setup();
    char buf[1024];
    return real_utimes(rewrite_path(pathname, buf, sizeof(buf)), times);
}

int lutimes(const char *pathname, const struct timeval times[2]) {
    setup();
    char buf[1024];
    return real_lutimes(rewrite_path(pathname, buf, sizeof(buf)), times);
}

int mknod(const char *pathname, mode_t mode, dev_t dev) {
    setup();
    char buf[1024];
    return real_mknod(rewrite_path(pathname, buf, sizeof(buf)), mode, dev);
}

int mknodat(int dirfd, const char *pathname, mode_t mode, dev_t dev) {
    setup();
    char buf[1024];
    return real_mknodat(dirfd, rewrite_at_path(dirfd, pathname, buf, sizeof(buf)), mode, dev);
}

int mkfifo(const char *pathname, mode_t mode) {
    setup();
    char buf[1024];
    return real_mkfifo(rewrite_path(pathname, buf, sizeof(buf)), mode);
}

int mkfifoat(int dirfd, const char *pathname, mode_t mode) {
    setup();
    char buf[1024];
    return real_mkfifoat(dirfd, rewrite_at_path(dirfd, pathname, buf, sizeof(buf)), mode);
}

void *dlopen(const char *filename, int flag) {
    setup();
    char buf[1024];
    const char *new_path = rewrite_path(filename, buf, sizeof(buf));
    void *ret = real_dlopen(new_path, flag);
    if (!ret) {
        __android_log_print(ANDROID_LOG_ERROR, "LorieNative Hook", "dlopen failed for %s -> %s: %s", filename, new_path, dlerror());
    }
    return ret;
}
