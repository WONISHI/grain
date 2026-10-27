#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pty.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>

#define MAX_SESSIONS 8

struct Session {
    int alive;
    int master;
    pid_t child;
};

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static struct Session sessions[MAX_SESSIONS];
static int sessions_ready = 0;
static char last_error[192];

static void ensure_sessions_locked(void) {
    if (sessions_ready) return;
    for (int i = 0; i < MAX_SESSIONS; i++) {
        sessions[i].alive = 0;
        sessions[i].master = -1;
        sessions[i].child = -1;
    }
    sessions_ready = 1;
}

static void remember(const char* prefix) {
    snprintf(last_error, sizeof last_error, "%s: %s", prefix, strerror(errno));
}

static void reap_locked(int index) {
    struct Session* session = &sessions[index];
    if (!session->alive && session->master < 0 && session->child <= 0) return;
    int fd = session->master;
    pid_t pid = session->child;
    session->alive = 0;
    session->master = -1;
    session->child = -1;
    if (pid > 0) {
        kill(-pid, SIGKILL);
        kill(pid, SIGKILL);
    }
    if (fd >= 0) close(fd);
    if (pid > 0) {
        int status = 0;
        waitpid(pid, &status, 0);
    }
}

static struct Session* lookup_locked(jint handle) {
    if (handle < 0 || handle >= MAX_SESSIONS || !sessions[handle].alive) return NULL;
    return &sessions[handle];
}

JNIEXPORT jstring JNICALL Java_app_grain_dev_GrainPty_lastError(JNIEnv* env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, last_error);
}

JNIEXPORT jint JNICALL Java_app_grain_dev_GrainPty_open(
        JNIEnv* env, jclass cls, jstring jcwd, jobjectArray jenv, jint cols, jint rows) {
    (void) cls;
    const char* cwd_utf = jcwd ? (*env)->GetStringUTFChars(env, jcwd, NULL) : NULL;
    char cwd[4096];
    snprintf(cwd, sizeof cwd, "%s", cwd_utf && cwd_utf[0] ? cwd_utf : "/");
    if (cwd_utf) (*env)->ReleaseStringUTFChars(env, jcwd, cwd_utf);

    int envc = jenv ? (*env)->GetArrayLength(env, jenv) : 0;
    char** envp = calloc((size_t) envc + 1, sizeof(char*));
    if (!envp) {
        snprintf(last_error, sizeof last_error, "%s", "内存不足");
        return -1;
    }
    for (int i = 0; i < envc; i++) {
        jstring item = (*env)->GetObjectArrayElement(env, jenv, i);
        const char* utf = (*env)->GetStringUTFChars(env, item, NULL);
        envp[i] = strdup(utf ? utf : "");
        if (utf) (*env)->ReleaseStringUTFChars(env, item, utf);
        (*env)->DeleteLocalRef(env, item);
    }

    if (cols < 1) cols = 80;
    if (rows < 1) rows = 24;
    struct winsize ws;
    memset(&ws, 0, sizeof ws);
    ws.ws_col = (unsigned short) cols;
    ws.ws_row = (unsigned short) rows;

    pthread_mutex_lock(&lock);
    ensure_sessions_locked();
    int slot = -1;
    for (int i = 0; i < MAX_SESSIONS; i++) {
        if (!sessions[i].alive && sessions[i].master < 0) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        pthread_mutex_unlock(&lock);
        snprintf(last_error, sizeof last_error, "%s", "终端数量已满");
        for (int i = 0; i < envc; i++) free(envp[i]);
        free(envp);
        return -1;
    }

    int master = -1;
    int slave = -1;
    if (openpty(&master, &slave, NULL, NULL, &ws) != 0) {
        remember("openpty");
        pthread_mutex_unlock(&lock);
        for (int i = 0; i < envc; i++) free(envp[i]);
        free(envp);
        return -1;
    }
    fcntl(master, F_SETFD, FD_CLOEXEC);

    char* argv[] = {"sh", "-i", NULL};
    pid_t pid = fork();
    if (pid < 0) {
        remember("fork");
        close(master);
        close(slave);
        pthread_mutex_unlock(&lock);
        for (int i = 0; i < envc; i++) free(envp[i]);
        free(envp);
        return -1;
    }
    if (pid == 0) {
        close(master);
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        chdir(cwd);
        execve("/system/bin/sh", argv, envp);
        _exit(127);
    }

    close(slave);
    sessions[slot].alive = 1;
    sessions[slot].master = master;
    sessions[slot].child = pid;
    pthread_mutex_unlock(&lock);
    for (int i = 0; i < envc; i++) free(envp[i]);
    free(envp);
    return slot;
}

JNIEXPORT jint JNICALL Java_app_grain_dev_GrainPty_read(
        JNIEnv* env, jclass cls, jint handle, jbyteArray array, jint timeout_ms) {
    (void) cls;
    pthread_mutex_lock(&lock);
    struct Session* session = lookup_locked(handle);
    int fd = session ? session->master : -1;
    pthread_mutex_unlock(&lock);
    if (fd < 0) return -2;

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    int ready = poll(&pfd, 1, timeout_ms);
    if (ready == 0) return 0;
    if (ready < 0) return errno == EINTR ? 0 : -1;

    pthread_mutex_lock(&lock);
    session = lookup_locked(handle);
    int current = session ? session->master : -1;
    pthread_mutex_unlock(&lock);
    if (current != fd) return -2;

    jsize cap = (*env)->GetArrayLength(env, array);
    char tmp[4096];
    size_t want = (size_t) cap < sizeof tmp ? (size_t) cap : sizeof tmp;
    ssize_t n = read(fd, tmp, want);
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, array, 0, (jsize) n, (const jbyte*) tmp);
        return (jint) n;
    }
    if (n < 0 && (errno == EINTR || errno == EAGAIN)) return 0;
    return -2;
}

JNIEXPORT jint JNICALL Java_app_grain_dev_GrainPty_write(
        JNIEnv* env, jclass cls, jint handle, jbyteArray array) {
    (void) cls;
    pthread_mutex_lock(&lock);
    struct Session* session = lookup_locked(handle);
    int fd = session ? session->master : -1;
    pthread_mutex_unlock(&lock);
    if (fd < 0) return -1;
    jsize len = (*env)->GetArrayLength(env, array);
    jbyte* bytes = (*env)->GetByteArrayElements(env, array, NULL);
    if (!bytes) return -1;
    jsize off = 0;
    while (off < len) {
        ssize_t n = write(fd, bytes + off, (size_t) (len - off));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        off += (jsize) n;
    }
    (*env)->ReleaseByteArrayElements(env, array, bytes, JNI_ABORT);
    return off == len ? 0 : -1;
}

JNIEXPORT void JNICALL Java_app_grain_dev_GrainPty_resize(
        JNIEnv* env, jclass cls, jint handle, jint cols, jint rows) {
    (void) env;
    (void) cls;
    if (cols < 1) cols = 1;
    if (rows < 1) rows = 1;
    pthread_mutex_lock(&lock);
    struct Session* session = lookup_locked(handle);
    int fd = session ? session->master : -1;
    pid_t pid = session ? session->child : -1;
    pthread_mutex_unlock(&lock);
    if (fd < 0) return;
    struct winsize ws;
    memset(&ws, 0, sizeof ws);
    ws.ws_col = (unsigned short) cols;
    ws.ws_row = (unsigned short) rows;
    ioctl(fd, TIOCSWINSZ, &ws);
    if (pid > 0) kill(pid, SIGWINCH);
}

JNIEXPORT void JNICALL Java_app_grain_dev_GrainPty_interrupt(JNIEnv* env, jclass cls, jint handle) {
    (void) env;
    (void) cls;
    pthread_mutex_lock(&lock);
    struct Session* session = lookup_locked(handle);
    int fd = session ? session->master : -1;
    pid_t pid = session ? session->child : -1;
    pthread_mutex_unlock(&lock);
    if (fd >= 0) {
        const char ctrl_c = 3;
        (void) write(fd, &ctrl_c, 1);
    }
    if (pid > 0) kill(-pid, SIGINT);
}

JNIEXPORT void JNICALL Java_app_grain_dev_GrainPty_close(JNIEnv* env, jclass cls, jint handle) {
    (void) env;
    (void) cls;
    if (handle < 0 || handle >= MAX_SESSIONS) return;
    pthread_mutex_lock(&lock);
    ensure_sessions_locked();
    reap_locked(handle);
    pthread_mutex_unlock(&lock);
}

JNIEXPORT void JNICALL Java_app_grain_dev_GrainPty_closeAll(JNIEnv* env, jclass cls) {
    (void) env;
    (void) cls;
    pthread_mutex_lock(&lock);
    ensure_sessions_locked();
    for (int i = 0; i < MAX_SESSIONS; i++) reap_locked(i);
    pthread_mutex_unlock(&lock);
}
