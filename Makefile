APK := app/build/outputs/apk/debug/app-debug.apk
PKG := com.example.hello

.PHONY: build deploy run clean

build:
	./gradlew assembleDebug -q

deploy: build
	adb wait-for-device
	adb install -r $(APK)

run: deploy
	adb shell am force-stop $(PKG)
	adb shell am force-stop com.google.android.documentsui
	adb shell monkey -p $(PKG) -c android.intent.category.LAUNCHER 1

clean:
	./gradlew clean -q
