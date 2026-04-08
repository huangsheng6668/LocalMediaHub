"""mDNS service registration for LocalMediaHub using zeroconf."""

from __future__ import annotations

import logging
import socket
import threading
from typing import Optional

from zeroconf import IPVersion
from zeroconf import ServiceInfo
from zeroconf import Zeroconf

logger = logging.getLogger(__name__)


class MDnsRegistrar:
    """Registers and unregisters an mDNS service in a background thread."""

    def __init__(self, port: int, service_type: str = "_localmediahub._tcp.local.",
                 service_name: str = "LocalMediaHub"):
        self.port = port
        self.service_type = service_type
        self.service_name = service_name
        self._zeroconf: Optional[Zeroconf] = None
        self._service_info: Optional[ServiceInfo] = None
        self._thread: Optional[threading.Thread] = None
        self._registered = threading.Event()

    def register(self) -> None:
        """Register mDNS service in a background thread (non-blocking)."""
        self._thread = threading.Thread(target=self._do_register, daemon=True)
        self._thread.start()

    def _do_register(self) -> None:
        try:
            self._zeroconf = Zeroconf(ip_version=IPVersion.V4Only)

            # Get local IP
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            local_ip = s.getsockname()[0]
            s.close()

            self._service_info = ServiceInfo(
                self.service_type,
                f"{self.service_name}.{self.service_type}",
                port=self.port,
                addresses=[local_ip],
            )
            self._zeroconf.register_service(self._service_info)
            self._registered.set()
            logger.info("mDNS service registered: %s on port %d", self.service_name, self.port)
        except Exception as e:
            logger.warning("mDNS registration failed: %s", e)

    def wait_for_registration(self, timeout: float = 5.0) -> None:
        """Wait until registration completes (for testing)."""
        self._registered.wait(timeout=timeout)

    def unregister(self) -> None:
        """Unregister mDNS service."""
        try:
            if self._zeroconf and self._service_info:
                self._zeroconf.unregister_service(self._service_info)
                self._zeroconf.close()
            logger.info("mDNS service unregistered: %s", self.service_name)
        except Exception as e:
            logger.warning("mDNS unregistration failed: %s", e)
