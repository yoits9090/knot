.PHONY: build run data test reference clean

JAR = build/deps/commons-math3-3.6.1.jar
SOURCES = $(shell find src/main/java -name '*.java')
TEST_SOURCES = $(shell find src/test/java -name '*.java' 2>/dev/null)
REFERENCE_DIR ?= src/test/resources/upstream

$(JAR):
	python3 scripts/fetch-dependencies.py

build: $(JAR)
	mkdir -p build/classes
	javac --release 21 -Xlint:all -cp $(JAR) -d build/classes $(SOURCES)

run: build
	java -cp 'build/classes:$(JAR)' org.mathhacks.Main $(ARGS)

data:
	python3 scripts/reference-check.py --regenerate

test: build
	@test -f "$(REFERENCE_DIR)/cases.csv" || { echo "Missing local reference data. Run 'make data' first." >&2; exit 1; }
	mkdir -p build/test-classes
	javac --release 21 -Xlint:all -cp 'build/classes:$(JAR)' -d build/test-classes $(TEST_SOURCES)
	java -ea -cp 'build/classes:build/test-classes:$(JAR)' org.mathhacks.multibind.CoreTest $(REFERENCE_DIR)
	java -ea -cp 'build/classes:build/test-classes:$(JAR)' org.mathhacks.multibind.InvestigationTest

reference:
	python3 scripts/reference-check.py

clean:
	rm -rf build/classes build/test-classes
