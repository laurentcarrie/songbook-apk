# The tablet has the release-signed app, which a debug build cannot replace:
# deploy the release apk when there is a signing key, the debug one otherwise.
HAS_KEY := $(wildcard keystore.properties)
ifdef HAS_KEY
APK := app/build/outputs/apk/release/laurent-zik-release.apk
BUILD := release
else
APK := app/build/outputs/apk/debug/laurent-zik-debug.apk
BUILD := build
endif
PKG := com.laurentcarrie.zik

.DEFAULT_GOAL := help
.PHONY: help build release gdrive bump deploy run clean

help: ## show this help
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-22s\033[0m %s\n", $$1, $$2}'

build: ## build the debug apk
	./gradlew assembleDebug -q

release: ## build the signed release apk (needs keystore.properties)
	@test -f keystore.properties || { echo "keystore.properties is missing: no signing key"; exit 1; }
	./gradlew assembleRelease -q
	@ls -l app/build/outputs/apk/release/laurent-zik-release.apk

GDRIVE_FILE := gdrive:apk/laurent-zik/laurent-zik.apk

gdrive: release ## build the release apk and upload it as laurent-zik.apk to gdrive:apk/laurent-zik with rclone
	rclone copyto -v app/build/outputs/apk/release/laurent-zik-release.apk $(GDRIVE_FILE)
	@rclone lsl $(GDRIVE_FILE)

bump: ## bump the version: versionCode + 1 and the last number of versionName (1.1 -> 1.2) in app/build.gradle
	@code=$$(sed -n "s/^ *versionCode \([0-9][0-9]*\)$$/\1/p" app/build.gradle); \
	name=$$(sed -n "s/^ *versionName '\(.*\)'$$/\1/p" app/build.gradle); \
	[ -n "$$code" ] && [ -n "$$name" ] || { echo "versionCode / versionName not found in app/build.gradle"; exit 1; }; \
	newname="$${name%.*}.$$(( $${name##*.} + 1 ))"; newcode=$$(( code + 1 )); \
	sed -i "s/^\( *versionCode \)[0-9]*$$/\1$$newcode/; s/^\( *versionName '\).*'$$/\1$$newname'/" app/build.gradle; \
	echo "version $$name ($$code) -> $$newname ($$newcode)"

deploy: $(BUILD) ## build and install the apk (release if keystore.properties exists) on the connected device (adb)
	adb wait-for-device
	adb install -r $(APK)

run: deploy ## deploy and launch the app
	adb shell monkey -p $(PKG) -c android.intent.category.LAUNCHER 1

clean: ## clean the build
	./gradlew clean -q
