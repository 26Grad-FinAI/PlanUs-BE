.PHONY: check fmt compile test fix

check: fmt compile test

fmt:
	./gradlew spotlessCheck

compile:
	./gradlew compileJava compileTestJava

test:
	./gradlew test

fix:
	./gradlew spotlessApply
