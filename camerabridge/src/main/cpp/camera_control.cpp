/*
 * camera_control.cpp — JNI layer for the *control* surface of libgphoto2:
 *
 *   - camera summary / model identification
 *   - generic configuration tree (P tp2 device properties): exposure triangle
 *     (shutterspeed, aperture, iso), white balance, picture style, battery …
 *   - storage (SD) information via gp_camera_get_storageinfo
 *   - still capture trigger (Canon EOS remote release) incl. Bulb toggle
 *   - Wi-Fi PTP/IP session initialization (port "ptpip:<ip>:<port>")
 *
 * Reuses the single global session defined in camera_bridge.cpp
 * (see gp_session.h) and always runs serialized behind the session mutex.
 */

#include "gp_session.h"

#include <cerrno>
#include <cstdlib>
#include <cstring>

#include <gphoto2/gphoto2.h>
#include <gphoto2/gphoto2-port.h>
#include <gphoto2/gphoto2-widget.h>
#include <gphoto2/gphoto2-filesys.h>

#include <pthread.h>

#ifndef __ANDROID__
#include <sys/time.h>
#endif

#define JNI_CLASS_PREFIX "Java_com_mav_cameranativebridge_NativeCameraInterface_"

namespace {

/* checks and throws on failure (JNI exception remains pending) */
bool ok(JNIEnv *env, int ret, const char *what)
{
	if (ret < GP_OK) {
		gpbridge::gpThrow(env, ret, what);
		return false;
	}
	return true;
}

jstring newString(JNIEnv *env, const char *text)
{
	if (text == nullptr)
		text = "";
	return env->NewStringUTF(text);
}

/* ------------------------------------------------------------------ */
/* value → string                                                      */
/* ------------------------------------------------------------------ */

bool widgetToString(JNIEnv *env, CameraWidget *w, char *buf, size_t bufSize)
{
	CameraWidgetType type = GP_WIDGET_WINDOW;
	if (gp_widget_get_type(w, &type) != GP_OK)
		return false;

	buf[0] = '\0';
	switch (type) {
	case GP_WIDGET_TEXT:
	case GP_WIDGET_RADIO:
	case GP_WIDGET_MENU:
	case GP_WIDGET_BUTTON: {
		char *val = nullptr;
		if (gp_widget_get_value(w, &val) != GP_OK || val == nullptr)
			return false;
		snprintf(buf, bufSize, "%s", val);
		return true;
	}
	case GP_WIDGET_TOGGLE: {
		int val = 0;
		if (gp_widget_get_value(w, &val) != GP_OK)
			return false;
		snprintf(buf, bufSize, "%d", val);
		return true;
	}
	case GP_WIDGET_RANGE: {
		float val = 0.0f;
		if (gp_widget_get_value(w, &val) != GP_OK)
			return false;
		snprintf(buf, bufSize, "%g", (double) val);
		return true;
	}
	case GP_WIDGET_DATE: {
		int val = 0;
		if (gp_widget_get_value(w, &val) != GP_OK)
			return false;
		snprintf(buf, bufSize, "%d", val);
		return true;
	}
	default:
		return false;
	}
}

/* ------------------------------------------------------------------ */
/* string → widget value (types mirrored from gphoto2-widget.h)         */
/* ------------------------------------------------------------------ */

bool setWidgetFromString(JNIEnv *env, CameraWidget *w, const char *value)
{
	CameraWidgetType type = GP_WIDGET_WINDOW;
	if (!ok(env, gp_widget_get_type(w, &type), "gp_widget_get_type"))
		return false;

	switch (type) {
	case GP_WIDGET_TEXT:
	case GP_WIDGET_BUTTON:
	case GP_WIDGET_RADIO:
	case GP_WIDGET_MENU:
		return ok(env, gp_widget_set_value(w, value), "gp_widget_set_value(str)");

	case GP_WIDGET_TOGGLE: {
		int val = (strcmp(value, "0") != 0 && strcmp(value, "false") != 0
					   && strcmp(value, "no") != 0) ? 1 : 0;
		return ok(env, gp_widget_set_value(w, &val), "gp_widget_set_value(toggle)");
	}
	case GP_WIDGET_RANGE: {
		float val = strtof(value, nullptr);
		return ok(env, gp_widget_set_value(w, &val), "gp_widget_set_value(range)");
	}
	case GP_WIDGET_DATE: {
		int val = (int) strtol(value, nullptr, 10);
		return ok(env, gp_widget_set_value(w, &val), "gp_widget_set_value(date)");
	}
	default:
		jniThrowFmt(env, "java/lang/IllegalArgumentException",
					"widget type %d cannot be set from a string", (int) type);
		return false;
	}
}

/* Called by gp_camera_list_config for Kotlin consumption */
jobjectArray listConfigNames(JNIEnv *env, Camera *camera, GPContext *ctx)
{
	CameraList *names = nullptr;
	if (gp_list_new(&names) != GP_OK) {
		jniThrowImpl(env, "java/io/IOException", "gp_list_new failed");
		return nullptr;
	}
	int ret = gp_camera_list_config(camera, names, ctx);
	if (ret < GP_OK) {
		gp_list_free(names);
		gpThrow(env, ret, "gp_camera_list_config");
		return nullptr;
	}

	const int n = gp_list_count(names);
	jclass stringClass = env->FindClass("java/lang/String");
	jobjectArray out = env->NewObjectArray(n, stringClass, nullptr);
	int written = 0;
	for (int i = 0; i < n; ++i) {
		const char *name = nullptr;
		if (gp_list_get_name(names, i, &name) != GP_OK || name == nullptr)
			continue;
		jstring jname = env->NewStringUTF(name);
		if (jname != nullptr) {
			env->SetObjectArrayElement(out, written++, jname);
			env->DeleteLocalRef(jname);
		}
	}
	jobjectArray exact = env->NewObjectArray(written, stringClass, nullptr);
	for (int i = 0; i < written; ++i) {
		jstring s = static_cast<jstring>(env->GetObjectArrayElement(out, i));
		env->SetObjectArrayElement(exact, i, s);
		env->DeleteLocalRef(s);
	}
	env->DeleteLocalRef(out);
	gp_list_free(names);
	return exact;
}

} /* anonymous namespace */

extern "C" {

/* ------------------------------------------------------------------ */
/* camera identification                                               */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_mav_cameranativebridge_NativeCameraInterface_nativeGetSummary(
		JNIEnv *env, jobject /*this*/)
{
	SessionGuard guard(env);
	Camera *camera = guard.cameraOrThrow();
	if (camera == nullptr)
		return nullptr;

	/* gp_camera_get_summary writes into a CameraText buffer */
	CameraText text;
	memset(&text, 0, sizeof(text));
	if (!ok(env, gp_camera_get_summary(camera, &text, guard.ctx()),
			"gp_camera_get_summary"))
		return nullptr;
	return newString(env, text.text);
}

JNIEXPORT jstring JNICALL
Java_com_mav_cameranativebridge_NativeCameraInterface_nativeGetCameraModel(
		JNIEnv *env, jobject /*this*/)
{
	SessionGuard guard(env);
	Camera *camera = guard.cameraOrThrow();
	if (camera == nullptr)
		return nullptr;

	CameraAbilities abilities;
	memset(&abilities, 0, sizeof(abilities));
	const char *model = "PTP Camera";
	if (gp_camera_get_abilities(camera, &abilities) == GP_OK &&
		abilities.model[0] != '\0')
		model = abilities.model;
	return newString(env, model);
}

/* ------------------------------------------------------------------ */
/* configuration tree (ptp2 device properties)                         */
/* ------------------------------------------------------------------ */

/* flat list of every config widget name the camlib exposes */
JNIEXPORT jobjectArray JNICALL
Java_com_mav_cameranativebridge_NativeCameraInterface_nativeListConfigNames(
		JNIEnv *env, jobject /*this*/)
{
	SessionGuard guard(env);
	Camera *camera = guard.cameraOrThrow();
	if (camera == nullptr)
		return nullptr;

	return listConfigNames(env, camera, guard.ctx());
}

/*
 * Reads one widget by name.
 * Returns: [name, label, type, readonly, current, choice0…] or nullptr
 * when the name is unknown (no exception — cheap existence probes).
 */
JNIEXPORT jobjectArray JNICALL
Java_com_mav_cameranativebridge_NativeCameraInterface_nativeGetConfig(
		JNIEnv *env, jobject /*this*/, jstring jName)
{
	SessionGuard guard(env);
	Camera *camera = guard.cameraOrThrow();
	if (camera == nullptr)
		return nullptr;

	const char *name = env->GetStringUTFChars(jName, nullptr);
	if (name == nullptr)
		return nullptr;

	CameraWidget *widget = nullptr;
	int ret = gp_camera_get_single_config(camera, name, &widget, guard.ctx());
	if (ret < GP_OK) {
		env->ReleaseStringUTFChars(jName, name);
		return nullptr;        /* unknown name → null, not an error */
	}
	if (widget == nullptr) {
		env->ReleaseStringUTFChars(jName, name);
		jniThrowImpl(env, "java/io/IOException", "config widget missing");
		return nullptr;
	}
	env->ReleaseStringUTFChars(jName, name);

	CameraWidgetType type = GP_WIDGET_WINDOW;
	int readonly = 0;
	char current[256] = "";
	const char *label = name;

	gp_widget_get_type(widget, &type);
	gp_widget_get_readonly(widget, &readonly);
	gp_widget_get_label(widget, &label);
	widgetToString(env, widget, current, sizeof(current));

	const int nChoices = (type == GP_WIDGET_MENU || type == GP_WIDGET_RADIO)
							 ? gp_widget_count_choices(widget)
							 : 0;

	jclass stringClass = env->FindClass("java/lang/String");
	jobjectArray out = env->NewObjectArray(5 + nChoices, stringClass, nullptr);
	char typeBuf[16];
	snprintf(typeBuf, sizeof(typeBuf), "%d", (int) type);
	char roBuf[16];
	snprintf(roBuf, sizeof(roBuf), "%d", readonly ? 1 : 0);

	jstring s0 = newString(env, label);
	jstring s1 = newString(env, typeBuf);
	jstring s2 = newString(env, roBuf);
	jstring s3 = newString(env, current);
	env->SetObjectArrayElement(out, 0, s0);
	env->SetObjectArrayElement(out, 1, s1);
	env->SetObjectArrayElement(out, 2, s2);
	env->SetObjectArrayElement(out, 3, s3);
	env->DeleteLocalRef(s0); env->DeleteLocalRef(s1);
	env->DeleteLocalRef(s2); env->DeleteLocalRef(s3);

	for (int i = 0; i < nChoices; ++i) {
		const char *choice = nullptr;
		if (gp_widget_get_choice(widget, i, &choice) == GP_OK && choice) {
			jstring c = newString(env, choice);
			env->SetObjectArrayElement(out, 4 + i, c);
			env->DeleteLocalRef(c);
		}
	}

	jobjectArray exact = env->NewObjectArray(5 + nChoices, stringClass, nullptr);
	for (int i = 0; i < 5 + nChoices; ++i) {
		jstring s = static_cast<jstring>(env->GetObjectArrayElement(out, i));
		env->SetObjectArrayElement(exact, i, s);
		env->DeleteLocalRef(s);
	}
	env->DeleteLocalRef(out);
	gp_widget_free(widget);
	return exact;
}

/**
 * Sets one widget by name from a string.
 * Applies via gp_camera_set_single_config (ptp2 native single-set path).
 */
JNIEXPORT jboolean JNICALL
Java_com_mav_cameranativebridge_NativeCameraInterface_nativeSetConfig(
		JNIEnv *env, jobject /*this*/, jstring jName, jstring jValue)
{
	SessionGuard guard(env);
	Camera *camera = guard.cameraOrThrow();
	if (camera == nullptr)
		return JNI_FALSE;

	const char *name  = env->GetStringUTFChars(jName, nullptr);
	const char *value = jValue ? env->GetStringUTFChars(jValue, nullptr) : "";
	if (name == nullptr || value == nullptr) {
		if (name)  env->ReleaseStringUTFChars(jName, name);
		if (jValue) env->ReleaseStringUTFChars(jValue, value);
		jniThrowImpl(env, "java/lang/IllegalArgumentException",
					 "config name required");
		return JNI_FALSE;
	}

	CameraWidget *widget = nullptr;
	int ret = gp_camera_get_single_config(camera, name, &widget, guard.ctx());
	if (ret < GP_OK || widget == nullptr) {
		env->ReleaseStringUTFChars(jName, name);
		env->ReleaseStringUTFChars(jValue, value);
		gpThrow(env, ret < GP_OK ? ret : GP_ERROR_BAD_PARAMETERS,
				"gp_camera_get_single_config(set)");
		return JNI_FALSE;
	}

	if (!setWidgetFromString(env, widget, value)) {
		gp_widget_free(widget);
		env->ReleaseStringUTFChars(jName, name);
		env->ReleaseStringUTFChars(jValue, value);
		return JNI_FALSE;
	}

	ret = gp_camera_set_single_config(camera, name, widget, guard.ctx());
	gp_widget_free(widget);
	env->ReleaseStringUTFChars(jName, name);
	env->ReleaseStringUTFChars(jValue, value);
	if (ret < GP_OK) {
		gpThrow(env, ret, "gp_camera_set_single_config");
		return JNI_FALSE;
	}
	LOGI("config '%s' set to '%s'", name, value);
	return JNI_TRUE;
}

/* ------------------------------------------------------------------ */
/* still capture (Canon EOS remote release)                            */
/* ------------------------------------------------------------------ */

/**
 * gp_camera_capture(GP_CAPTURE_IMAGE).
 * For Canon EOS the ptp2 camlib prepares remote capture itself; when the
 * `shutterspeed` config is "bulb" the first call starts and the second
 * call ends the exposure — the equivalent CLI is `gphoto2 --capture-image`.
 *
 * Returns the captured file as "folder\nname" (folder may be "" if the
 * camlib does not report it).
 */
JNIEXPORT jstring JNICALL
Java_com_mav_cameranativebridge_NativeCameraInterface_nativeCaptureImage(
		JNIEnv *env, jobject /*this*/)
{
	SessionGuard guard(env);
	Camera *camera = guard.cameraOrThrow();
	if (camera == nullptr)
		return nullptr;

	CameraFilePath path;
	memset(&path, 0, sizeof(path));
	int ret = gp_camera_capture(camera, GP_CAPTURE_IMAGE, &path, guard.ctx());
	if (ret < GP_OK) {
		gpThrow(env, ret, "gp_camera_capture");
		return nullptr;
	}

	char buf[2048];
	snprintf(buf, sizeof(buf), "%s\n%s", path.folder, path.name);
	LOGI("capture: folder='%s' name='%s'", path.folder, path.name);
	return newString(env, buf);
}

/* ------------------------------------------------------------------ */
/* storage (SD card)                                                   */
/* ------------------------------------------------------------------ */

/**
 * One string per storage:
 *   "basedir\tlabel\tdescription\tcapacityKB\tfreeKB\tfreeImages"
 */
JNIEXPORT jobjectArray JNICALL
Java_com_mav_cameranativebridge_NativeCameraInterface_nativeGetStorageInfo(
		JNIEnv *env, jobject /*this*/)
{
	SessionGuard guard(env);
	Camera *camera = guard.cameraOrThrow();
	if (camera == nullptr)
		return nullptr;

	CameraStorageInformation *storages = nullptr;
	int nstorages = 0;
	int ret = gp_camera_get_storageinfo(camera, &storages, &nstorages,
										guard.ctx());
	if (ret < GP_OK || nstorages <= 0 || storages == nullptr) {
		if (storages != nullptr)
			free(storages);
		/* not an error condition for the UI — SD info unknown */
		jclass stringClass = env->FindClass("java/lang/String");
		jobjectArray empty = env->NewObjectArray(0, stringClass, nullptr);
		return empty;
	}

	jclass stringClass = env->FindClass("java/lang/String");
	jobjectArray out = env->NewObjectArray(nstorages, stringClass, nullptr);
	for (int i = 0; i < nstorages; ++i) {
		const CameraStorageInformation &si = storages[i];
		bool hasCapacity = si.fields & GP_STORAGEINFO_MAXCAPACITY;
		bool hasFree     = si.fields & GP_STORAGEINFO_FREESPACEKBYTES;
		bool hasImages   = si.fields & GP_STORAGEINFO_FREESPACEIMAGES;

		char line[1200];
		snprintf(line, sizeof(line), "%s\t%s\t%s\t%llu\t%llu\t%llu",
				 si.basedir,
				 (si.fields & GP_STORAGEINFO_LABEL) ? si.label : "",
				 si.description,
				 hasCapacity ? (unsigned long long) si.capacitykbytes : 0ULL,
				 hasFree     ? (unsigned long long) si.freekbytes : 0ULL,
				 hasImages   ? (unsigned long long) si.freeimages : 0ULL);
		jstring s = newString(env, line);
		if (s != nullptr) {
			env->SetObjectArrayElement(out, i, s);
			env->DeleteLocalRef(s);
		}
	}
	free(storages);
	return out;
}

/* ------------------------------------------------------------------ */
/* early-up: storage readback for the Kotlin battery parser             */
/* ------------------------------------------------------------------ */

/**
 * Convenience: read a single text-ish config and return its raw value,
 * or null when it does not exist. Used by Kotlin for "batterylevel".
 */
JNIEXPORT jstring JNICALL
Java_com_mav_cameranativebridge_NativeCameraInterface_nativeReadConfigText(
		JNIEnv *env, jobject /*this*/, jstring jName)
{
	SessionGuard guard(env);
	Camera *camera = guard.cameraOrThrow();
	if (camera == nullptr)
		return nullptr;

	const char *name = env->GetStringUTFChars(jName, nullptr);
	if (name == nullptr)
		return nullptr;

	CameraWidget *widget = nullptr;
	int ret = gp_camera_get_single_config(camera, name, &widget, guard.ctx());
	if (ret < GP_OK || widget == nullptr) {
		env->ReleaseStringUTFChars(jName, name);
		return nullptr;
	}
	env->ReleaseStringUTFChars(jName, name);

	char buf[256];
	jstring out = widgetToString(env, widget, buf, sizeof(buf))
					  ? newString(env, buf)
					  : nullptr;
	gp_widget_free(widget);
	return out;
}

/* ------------------------------------------------------------------ */
/* Wi-Fi: PTP/IP session over plain TCP (e.g. Canon camera hotspot)     */
/* ------------------------------------------------------------------ */

/*
 * gp_port_usb_set_sys_device(-1) resets any stale UsbManager fd, then a
 * regular browser-style session is configured. The ptp2 camlib performs
 * the PTP/IP negotiation itself (ptp_ptpip_connect) from the port path.
 *
 * libgphoto2 uses "ptpip:<host>:<port>" — the leading token before the
 * first ':' is discarded by the camlib; the address must be a literal
 * IPv4 address (the camlib uses inet_aton()).
 */
JNIEXPORT jboolean JNICALL
Java_com_mav_cameranativebridge_NativeCameraInterface_nativeInitWithIp(
		JNIEnv *env, jclass /*clazz*/, jstring jHost, jint port,
		jstring nativeLibDir)
{
	if (jHost == nullptr) {
		jniThrowImpl(env, "java/lang/IllegalArgumentException",
					 "host required");
		return JNI_FALSE;
	}
	const char *cppHost = env->GetStringUTFChars(jHost, nullptr);
	const char *cppLibDir =
		nativeLibDir ? env->GetStringUTFChars(nativeLibDir, nullptr) : nullptr;

	pthread_mutex_lock(&g_session_mutex);

	if (g_session.camera != nullptr) {
		pthread_mutex_unlock(&g_session_mutex);
		if (cppHost) env->ReleaseStringUTFChars(jHost, cppHost);
		if (cppLibDir) env->ReleaseStringUTFChars(nativeLibDir, cppLibDir);
		jniThrowImpl(env, "java/lang/IllegalStateException",
					 "camera already initialized; call close() first");
		return JNI_FALSE;
	}

	char ipPath[512];
	snprintf(ipPath, sizeof(ipPath), "ptpip:%s:%d", cppHost, (int) port);
	if (cppHost) env->ReleaseStringUTFChars(jHost, cppHost);

	int ret;
	const char *failWhat = "";

	/* 0. route gp_log (camlib scanning / dlopen diagnostics) to logcat */
	ensureGpLogging();

	/* 1. camlib/iolib search paths */
	setLibDirs(cppLibDir);
	if (cppLibDir) env->ReleaseStringUTFChars(nativeLibDir, cppLibDir);
	gp_port_usb_set_sys_device(-1);   /* clear any stale USB fd binding */

	/* 2. context */
	g_session.context = gp_context_new();
	gp_context_set_error_func(g_session.context,
		[](GPContext *, const char *text, void *) { LOGE("gp error: %s", text); },
		nullptr);
	gp_context_set_message_func(g_session.context,
		[](GPContext *, const char *text, void *) { LOGI("gp msg: %s", text); },
		nullptr);

	/* 3. camera + abilities */
	gp_camera_new(&g_session.camera);
	{
		CameraAbilitiesList *abilities = nullptr;
		do {
			if ((ret = gp_abilities_list_new(&abilities)) < GP_OK) { failWhat = "gp_abilities_list_new"; break; }
			if ((ret = gp_abilities_list_load(abilities, g_session.context)) < GP_OK) { failWhat = "gp_abilities_list_load"; break; }
			CameraAbilities apt;
			if ((ret = chooseAbilities(abilities, &apt)) < GP_OK) { failWhat = "no usable camlib (libptp2.so dlopen failed?)"; break; }
			if ((ret = gp_camera_set_abilities(g_session.camera, apt)) < GP_OK) { failWhat = "gp_camera_set_abilities"; break; }
		} while (false);
		if (abilities) gp_abilities_list_free(abilities);
		if (ret < GP_OK) goto fail;
	}

	/* 4. port lookup: "ptpip:<host>:<port>" */
	{
		GPPortInfoList *ports = nullptr;
		if ((ret = gp_port_info_list_new(&ports)) < GP_OK) { failWhat = "gp_port_info_list_new"; goto fail; }
		if ((ret = gp_port_info_list_load(ports)) < GP_OK) {
			failWhat = "gp_port_info_list_load";
			gp_port_info_list_free(ports);
			goto fail;
		}
		int index = gp_port_info_list_lookup_path(ports, ipPath);
		if (index < GP_OK) {
			failWhat = "look up ptpip port";
			gp_port_info_list_free(ports);
			ret = index;
			goto fail;
		}
		GPPortInfo info = nullptr;
		if ((ret = gp_port_info_list_get_info(ports, index, &info)) < GP_OK) {
			failWhat = "gp_port_info_list_get_info";
			gp_port_info_list_free(ports);
			goto fail;
		}
		if ((ret = gp_camera_set_port_info(g_session.camera, info)) < GP_OK) {
			failWhat = "gp_camera_set_port_info";
			gp_port_info_list_free(ports);
			goto fail;
		}
		gp_port_info_list_free(ports);
	}

	/* 5. connect (PTP/IP handshake happens inside the ptp2 camlib) */
	if ((ret = gp_camera_init(g_session.camera, g_session.context)) < GP_OK) {
		failWhat = "gp_camera_init";
		goto fail;
	}

	g_session.inited = true;
	LOGI("camera session initialized (ptpip %s)", ipPath);
	pthread_mutex_unlock(&g_session_mutex);
	return JNI_TRUE;

fail:
	LOGE("%s: %s (%d)", failWhat, gp_result_as_string(ret), ret);
	gp_camera_free(g_session.camera);
	g_session.camera  = nullptr;
	g_session.inited  = false;
	gp_context_unref(g_session.context);
	g_session.context = nullptr;
	pthread_mutex_unlock(&g_session_mutex);
	gpThrow(env, ret, failWhat);
	return JNI_FALSE;
}

} /* extern "C" */
