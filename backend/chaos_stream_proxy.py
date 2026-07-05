import socket
import threading
import time
import random

# Engineering settings for the emulator
SOURCE_PORT = 8554      # Port from real video stream source (Camera/Backend)
PROXY_PORT = 9554       # New port the smartwatch will connect to for the stream
BUFFER_SIZE = 65535

class ChaosStreamProxy:
    def __init__(self):
        # Array of active chaos scenarios
        self.packet_drop_rate = 0.0      # Packet drop ratio (0.0 to 1.0)
        self.jitter_max_ms = 0           # Max random delay per packet in ms
        self.burst_drop_active = False   # Simulate complete network outage (Dead Zone)
        self.corrupt_byte_rate = 0.0     # Ratio of data destruction inside a packet

    def handle_client(self, client_socket):
        print("[CHAOS PROXY] Watch connected! Hooking into source stream...")
        source_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            source_socket.connect(("127.0.0.1", SOURCE_PORT))
        except Exception as e:
            print(f"[CHAOS PROXY] Critical: Cannot connect to real source stream: {e}")
            client_socket.close()
            return

        def forward_to_watch():
            while True:
                try:
                    data = source_socket.recv(BUFFER_SIZE)
                    if not data: break
                    
                    # 1. Simulate sudden complete outage (Burst Drop)
                    if self.burst_drop_active:
                        continue # Drop packet completely without forwarding to watch
                        
                    # 2. Simulate random packet dropping (Packet Drop)
                    if random.random() < self.packet_drop_rate:
                        print("[CHAOS] Drop Packet Triggered!")
                        continue
                        
                    # 3. Simulate encryption and data destruction (Data Corruption)
                    if self.corrupt_byte_rate > 0.0:
                        data = bytearray(data)
                        for i in range(len(data)):
                            if random.random() < self.corrupt_byte_rate:
                                data[i] = random.randint(0, 255)
                        data = bytes(data)

                    # 4. Simulate wireless fluctuation (Jitter Delay)
                    if self.jitter_max_ms > 0:
                        delay = random.randint(0, self.jitter_max_ms) / 1000.0
                        time.sleep(delay)

                    client_socket.sendall(data)
                except Exception as e:
                    print(f"[CHAOS PROXY] Connection to watch severed: {e}")
                    break
            cleanup()

        def cleanup():
            try:
                client_socket.close()
            except OSError:
                pass
            try:
                source_socket.close()
            except OSError:
                pass

        threading.Thread(target=forward_to_watch, daemon=True).start()

    def start(self):
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("0.0.0.0", PROXY_PORT))
        server.listen(5)
        print(f"[CHAOS PROXY] Chaos Gateway running on port {PROXY_PORT}...")
        
        # Dedicated thread for real-time chaos control via Terminal
        threading.Thread(target=self.cli_control, daemon=True).start()

        while True:
            client_sock, _ = server.accept()
            threading.Thread(target=self.handle_client, args=(client_sock,), daemon=True).start()

    def cli_control(self):
        """ Instant control panel to inject faults live and monitor RecoveryManager on watch """
        while True:
            print("\n--- CHAOS INJECTION PANEL ---")
            print("1. Inject Packet Loss (e.g. 20%)")
            print("2. Inject Jitter (e.g. 100ms)")
            print("3. Trigger Total Network Blackout (Dead Zone)")
            print("4. Inject Bit-Flip Data Corruption")
            print("5. Reset to Perfect Network (Clear Chaos)")
            try:
                choice = input("Enter choice: ")
            except EOFError:
                return

            if choice == "1":
                self.packet_drop_rate = float(input("Enter drop rate (0.0 to 1.0): "))
            elif choice == "2":
                self.jitter_max_ms = int(input("Enter max jitter in ms: "))
            elif choice == "3":
                self.burst_drop_active = not self.burst_drop_active
                print(f"Burst Drop Active Status: {self.burst_drop_active}")
            elif choice == "4":
                self.corrupt_byte_rate = float(input("Enter corruption byte rate (e.g. 0.01): "))
            elif choice == "5":
                self.packet_drop_rate = 0.0
                self.jitter_max_ms = 0
                self.burst_drop_active = False
                self.corrupt_byte_rate = 0.0
                print("[CHAOS CLEAR] Network reverted to baseline pristine state.")

if __name__ == "__main__":
    proxy = ChaosStreamProxy()
    proxy.start()
