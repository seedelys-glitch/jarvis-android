#!/bin/bash
# Installe l'appli, donne les droits, la lance 90 s, et garde le journal Android.
set -x
APK=app/build/outputs/apk/debug/app-debug.apk
adb install -r -g $APK
adb shell pm grant fr.jabran.jarvis android.permission.RECORD_AUDIO || true
adb shell pm grant fr.jabran.jarvis android.permission.POST_NOTIFICATIONS || true
adb shell input keyevent 224; adb shell wm dismiss-keyguard; adb shell input keyevent 82; sleep 2
adb shell settings put system screen_off_timeout 1800000
adb logcat -c
adb shell am start -n fr.jabran.jarvis/.MainActivity
sleep 90
adb shell pidof fr.jabran.jarvis && echo "APPLI VIVANTE" || echo "APPLI MORTE"
adb shell dumpsys activity services fr.jabran.jarvis | grep -i "EcouteJarvis\|isForeground" | head
adb logcat -d > logcat.txt
grep -E "AndroidRuntime|FATAL|JarvisReveil|DEBUG  |sherpa|crash_dump|F libc|E libc|ActivityManager: (Process|Killing|Force)" logcat.txt | head -150 > logcat-resume.txt
cat logcat-resume.txt
true
