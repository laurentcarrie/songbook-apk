APK := app/build/outputs/apk/debug/app-debug.apk
PKG := com.laurentcarrie.zik

.DEFAULT_GOAL := help
.PHONY: help build release deploy run clean

help: ## show this help
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-22s\033[0m %s\n", $$1, $$2}'

build: ## build the debug apk
	./gradlew assembleDebug -q

release: ## build the signed release apk (needs keystore.properties)
	@test -f keystore.properties || { echo "keystore.properties is missing: no signing key"; exit 1; }
	./gradlew assembleRelease -q
	@ls -l app/build/outputs/apk/release/app-release.apk

deploy: build ## build and install the apk on the connected device (adb)
	adb wait-for-device
	adb install -r $(APK)

run: deploy ## deploy and launch the app
	adb shell monkey -p $(PKG) -c android.intent.category.LAUNCHER 1

clean: ## clean the build
	./gradlew clean -q
