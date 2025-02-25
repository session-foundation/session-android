H_FILES = $(wildcard ../../include/session/config/*.h ../../include/session/config/*/*.h)
HPP_FILES = $(wildcard ../../include/session/config/*.hpp ../../include/session/config/*/*.hpp)

.PHONY: all
all: h-docs hpp-docs

.PHONY: hpp-docs
hpp-docs:
	./make-docs.sh libsession-util-cpp $(HPP_FILES)

.PHONY: h-docs
h-docs:
	./make-docs.sh libsession-util-c $(H_FILES)

.PHONY: run-c
run-c:
	docsify serve libsession-util-c

.PHONY: run-cpp
run-cpp:
	docsify serve libsession-util-cpp


.PHONY: clean
clean:
	rm -rf ./libsession-util-c ./libsession-util-cpp
