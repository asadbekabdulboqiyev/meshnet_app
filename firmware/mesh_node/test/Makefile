CC      ?= cc
CFLAGS  ?= -Wall -Wextra -O2 -std=c11

SRCS    = ../mesh_frame.c ../relay.c
BIN     = test_relay

$(BIN): test_relay.c $(SRCS) ../mesh_frame.h ../relay.h
	$(CC) $(CFLAGS) -I.. test_relay.c $(SRCS) -o $(BIN)

run: $(BIN)
	./$(BIN)

clean:
	rm -f $(BIN)

.PHONY: run clean
