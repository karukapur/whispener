# Keep Listener's whisper.cpp JNI entry points.
-keepclasseswithmembers class * { native <methods>; }
-keep class com.listener.app.speech.JniWhisperEngine { native <methods>; }
