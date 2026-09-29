# USB0 Manager v1.4.0 — R36S aggressive Auto Configure

Targets R36S Android 11 arm64-v8a.

Auto Configure now backs up network/USB state, brings usb0 up, discovers the dynamic gateway, tries normal neighbor discovery, learns the peer MAC only from actual ARP/neighbor data, installs a verified neighbor entry when a MAC is learned, and uses BusyBox arp when available. If usb0 is absent it can attempt `svc usb setFunctions rndis` and `setprop sys.usb.config rndis` as a last-resort gadget recovery. If usb0 already exists, it deliberately does not toggle gadget mode.

The app never uses its own usb0 MAC as the peer MAC. If the TCL never supplies a peer MAC, the app reports that a static ARP entry cannot safely be constructed.

The UI is vertically scrollable and all actions are full-width for the R36S screen.
