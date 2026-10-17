.PHONY: build run clean

build:
	mkdir -p build/classes
	javac --release 21 -d build/classes src/main/java/org/mathhacks/Main.java

run: build
	java -cp build/classes org.mathhacks.Main

clean:
	rm -rf build
