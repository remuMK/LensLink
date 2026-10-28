/*
 * gp_session.h — internal shared declarations between the two JNI TUs.
 *
 *  camera_bridge.cpp   — session lifecycle, preview, filesystem, download
 *  camera_control.cpp  — summary/config control, capture, storage info, IP mode
 *
 * libgphoto2 is NOT thread-safe per Camera object, therefore every JNI entry
 * point serializes on g_session_mutex (see SessionGuard).
 */
#ifndef ANDROID_CAMERA_BRIDGE_GP_SESSION_H
#define ANDROID_CAMERA_BRIDGE_GP_SESSION_H

#include <jni.h>

#include <cstdio>
#include <cstring>

#include <gphoto2/gphoto2.h>
#include <gphoto2/gphoto2-port-log.h>
#include <gphoto2/gphoto2-result.h>

#include <pthread.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "CameraNativeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#else
/* host smoke build (see CMakeLists.txt): log to stderr */
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, "I: " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGE(...) do { fprintf(stderr, "E: " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGW(...) do { fprintf(stderr, "W: " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

namespace gpbridge {

struct CameraSession {
	Camera    *camera  = nullptr;
	GPContext *context = nullptr;
	bool       inited  = false;
};

/* defined in camera_bridge.cpp — single global session for the process */
extern CameraSession   g_session;
extern pthread_mutex_t g_session_mutex;

/* error helpers (defined in camera_bridge.cpp) */
void jniThrowImpl(JNIEnv *env, const char *cls, const char *msg);
void jniThrowFmt(JNIEnv *env, const char *cls, const char *fmt, ...);
void gpThrow(JNIEnv *env, int ret, const char *what);
/* local `ok()` helpers may be redefined per TU on top of gpThrow */

/* sets IOLIBS/CAMLIBS to the app's nativeLibraryDir */
void setLibDirs(const char *nativeLibDir);

/**
 * Routes libgphoto2's internal gp_log stream (camlib dir scans, lt_dlopen
 * failures, PTP trace lines) into logcat. Without a handler all GP_LOG_D
 * output is dropped, which made runtime dlopen problems impossible to see
 * on a device. Register once from any native init entry point.
 */
inline void ensureGpLogging()
{
	static bool registered = false;
	if (registered)
		return;
	registered = true;
#ifdef __ANDROID__
	static auto log = [](GPLogLevel level, const char *domain, const char *str, void *) {
		android_LogPriority pri = ANDROID_LOG_DEBUG;
		switch (level) {
		case GP_LOG_ERROR: pri = ANDROID_LOG_ERROR; break;
		case GP_LOG_DEBUG: pri = ANDROID_LOG_INFO;  break;
		default:           pri = ANDROID_LOG_DEBUG; break; /* DATA dumps */
		}
		__android_log_print(pri, "GPhoto2", "%s: %s", domain ? domain : "gp", str);
	};
	gp_log_add_func(GP_LOG_DEBUG, log, nullptr);
#else
	static auto log = [](GPLogLevel, const char *domain, const char *str, void *) {
		fprintf(stderr, "[gp] %s: %s\n", domain ? domain : "gp", str);
	};
	gp_log_add_func(GP_LOG_DEBUG, log, nullptr);
#endif
}

/**
 * Selects the PTP2 camlib. Only the ptp2 camlib is shipped, but match by
 * well-known ability ids so a future multi-camlib AAR still works.
 * (inline in the shared header — used by both JNI TUs)
 */
inline int chooseAbilities(CameraAbilitiesList *abilities, CameraAbilities *out)
{
	int count = gp_abilities_list_count(abilities);
	if (count < GP_OK)
		return count;

	static const char *const preferred[] = {
		"PTP",
		"Canon EOS",
		"Canon",
	};

	for (const char *want : preferred) {
		size_t wantLen = strlen(want);
		for (int i = 0; i < count; ++i) {
			CameraAbilities tmp;
			if (gp_abilities_list_get_abilities(abilities, i, &tmp) != GP_OK)
				continue;
			if (strncmp(tmp.id, want, wantLen) == 0) {
				*out = tmp;
				return GP_OK;
			}
		}
	}
	/* fallback: keep whatever the single shipped camlib advertises */
	if (count > 0)
		return gp_abilities_list_get_abilities(abilities, count - 1, out);
	return GP_ERROR;
}

/* RAII mutex guard: libgphoto2 must never see concurrent calls. */
struct SessionGuard {
	explicit SessionGuard(JNIEnv *env)
			: env(env) { pthread_mutex_lock(&g_session_mutex); }
	~SessionGuard() { pthread_mutex_unlock(&g_session_mutex); }

	Camera *cameraOrThrow()
	{
		if (g_session.camera == nullptr || !g_session.inited) {
			jniThrowImpl(env, "java/lang/IllegalStateException",
						 "camera not initialized; call init() first");
			return nullptr;
		}
		return g_session.camera;
	}
	GPContext *ctx() const { return g_session.context; }

	JNIEnv *env;
};

} /* namespace gpbridge */

/* unqualified names in the TUs, mirroring the original layout */
using gpbridge::CameraSession;
using gpbridge::g_session;
using gpbridge::g_session_mutex;
using gpbridge::SessionGuard;
using gpbridge::jniThrowImpl;
using gpbridge::jniThrowFmt;
using gpbridge::gpThrow;
using gpbridge::setLibDirs;
using gpbridge::ensureGpLogging;
using gpbridge::chooseAbilities;

#endif /* ANDROID_CAMERA_BRIDGE_GP_SESSION_H */
