#include "dareka_NLMain.h"
#include "jawt_md.h"
#include <windows.h>

static WNDPROC lpPrevWndProc = NULL;
static jclass NLMain = NULL;
static bool disableSuspend = false;

void callStaticVoidMethod(const char *methodName) {
	JavaVM *vm;
	jsize ct;
	if (NLMain == NULL || JNI_GetCreatedJavaVMs(&vm, 1, &ct) != JNI_OK) {
		return;
	}
	JNIEnv *env;
	if (vm->AttachCurrentThread((void**)&env, NULL) == JNI_OK) {
		jmethodID id = env->GetStaticMethodID(NLMain, methodName, "()V");
		if (id != NULL) {
			env->CallStaticVoidMethod(NLMain, id);
		} else {
			env->ExceptionClear();
		}
		vm->DetachCurrentThread();
	}
}

LRESULT CALLBACK NLWindowProc(HWND hWnd, UINT iMsg, WPARAM wParam, LPARAM lParam) {
	LRESULT lResult = CallWindowProc(lpPrevWndProc, hWnd, iMsg, wParam, lParam);
	switch (iMsg) {
	case WM_QUERYENDSESSION:
		return TRUE; // シャットダウンを許可(無くても良いが念のため)
	case WM_ENDSESSION:
		callStaticVoidMethod("shutdown");
		DestroyWindow(hWnd); // Destroyしないと「応答無し」になる
		return 0;
	case WM_POWERBROADCAST:
		if (disableSuspend && wParam == PBT_APMQUERYSUSPEND) {
			return BROADCAST_QUERY_DENY; // サスペンド禁止
		} else if (wParam == PBT_APMSUSPEND) {
			callStaticVoidMethod("disconnect");
		}
		break;
	}
	return lResult;
}

JNIEXPORT void JNICALL Java_dareka_NLMain_setNativeWindowProc
  (JNIEnv *env, jclass nlMain, jobject frame, jstring params)
{
	// See: http://terai.xrea.jp/Tips/JNI_HWND.html
	JAWT awt;
	awt.version = JAWT_VERSION_1_4;
	if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
		env->FatalError("JAWT_GetAWT() == JNI_FALSE");
		return;
	}
	JAWT_DrawingSurface *ds = awt.GetDrawingSurface(env, frame);
	if (ds == NULL) {
		env->FatalError("GetDrawingSurface() == NULL");
		return;
	}
	jint lock = ds->Lock(ds);
	if ((lock & JAWT_LOCK_ERROR) != 0) {
		awt.FreeDrawingSurface(ds);
		env->FatalError("JAWT_LOCK_ERROR");
		return;
	}
	JAWT_DrawingSurfaceInfo *dsi = ds->GetDrawingSurfaceInfo(ds);
	if (dsi == NULL) {
		ds->Unlock(ds);
		awt.FreeDrawingSurface(ds);
		env->FatalError("GetDrawingSurfaceInfo() == NULL");
		return;
	}

	// オプションパラメータ処理
	const char *utf8;
	if (params != NULL && (utf8 = env->GetStringUTFChars(params, NULL)) != NULL) {
		if (strstr(utf8, "DisableSuspend=true;") != NULL) {
			disableSuspend = true;
			SetThreadExecutionState(ES_SYSTEM_REQUIRED | ES_CONTINUOUS);
		}
		env->ReleaseStringUTFChars(params, utf8);
	}

	// 後で使うのでグローバル参照にして保存しておく
	NLMain = static_cast<jclass>(env->NewGlobalRef(nlMain));

	// ウインドウプロシージャを差し替える
	lpPrevWndProc = (WNDPROC)SetWindowLongPtr(
		((JAWT_Win32DrawingSurfaceInfo*)dsi->platformInfo)->hwnd,
		GWLP_WNDPROC, (LONG_PTR)NLWindowProc);

	ds->FreeDrawingSurfaceInfo(dsi);
	ds->Unlock(ds);
	awt.FreeDrawingSurface(ds);
}
