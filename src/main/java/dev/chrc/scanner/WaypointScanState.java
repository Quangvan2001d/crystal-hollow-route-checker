package dev.chrc.scanner;

public enum WaypointScanState {
    /** This waypoint is not the next route entry yet. */
    WAITING_FOR_PREVIOUS,
    /** The chunk containing the waypoint has not been delivered to this client. */
    NOT_LOADED,
    /** The waypoint chunk is loaded, but one or more chunks touched by the scan cube are not. */
    WAITING_FOR_SCAN_AREA,
    READY,
    SCANNED
}
