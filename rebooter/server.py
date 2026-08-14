"""Rebooter-Sidecar: startet auf Zuruf alle Container des eigenen Compose-Projekts neu.

Genau ein Endpunkt: POST /reboot mit Header X-Rebooter-Token. Der Sidecar
antwortet sofort mit 202 und fuehrt den Neustart asynchron aus, damit die
Antwort den Aufrufer noch erreicht, bevor das Backend selbst neu startet.

Der eigene Container wird ausgenommen: der Sidecar ist zustandslos, und ein
Selbst-Neustart wuerde nur den laufenden Restart-Loop abbrechen.

Ohne konfiguriertes REBOOTER_TOKEN startet der Prozess nicht (fail-closed).
"""

import http.server
import os
import socket
import subprocess
import sys
import threading
import time

PORT = int(os.environ.get("PORT", "8095"))
TOKEN = os.environ.get("REBOOTER_TOKEN", "").strip()
TOKEN_HEADER = "X-Rebooter-Token"
COMPOSE_PROJECT_LABEL = "com.docker.compose.project"
RESTART_DELAY_SECONDS = 1.0


def log(message: str) -> None:
    print(message, flush=True)


def docker(*args: str) -> str:
    result = subprocess.run(
        ["docker", *args], capture_output=True, text=True, timeout=120
    )
    if result.returncode != 0:
        raise RuntimeError(f"docker {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout.strip()


def own_container_id() -> str:
    """Die eigene Container-ID; der Container-Hostname ist deren Kurzform."""
    return docker(
        "inspect", "--format", "{{.Id}}", socket.gethostname()
    )


def restart_project_containers() -> None:
    own_id = own_container_id()
    project = docker(
        "inspect",
        "--format",
        '{{ index .Config.Labels "' + COMPOSE_PROJECT_LABEL + '" }}',
        own_id,
    )
    if not project:
        raise RuntimeError("Eigenes Compose-Projekt-Label nicht ermittelbar")
    container_ids = docker(
        "ps", "-q", "--filter", f"label={COMPOSE_PROJECT_LABEL}={project}"
    ).split()
    targets = [cid for cid in container_ids if not own_id.startswith(cid)]
    log(f"Starte {len(targets)} Container des Projekts '{project}' neu")
    for cid in targets:
        try:
            docker("restart", cid)
            log(f"Neu gestartet: {cid}")
        except Exception as ex:  # ein hartnaeckiger Container stoppt die uebrigen nicht
            log(f"Neustart von {cid} fehlgeschlagen: {ex}")


def restart_later() -> None:
    time.sleep(RESTART_DELAY_SECONDS)
    try:
        restart_project_containers()
    except Exception as ex:
        log(f"Neustart fehlgeschlagen: {ex}")


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802 (http.server-Namenskonvention)
        if self.path != "/reboot":
            self.send_error(404)
            return
        if self.headers.get(TOKEN_HEADER, "") != TOKEN:
            log("Reboot abgelehnt: falscher oder fehlender Token")
            self.send_error(403)
            return
        log("Reboot angefordert")
        threading.Thread(target=restart_later, daemon=True).start()
        self.send_response(202)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):  # noqa: N802
        self.send_error(404)

    def log_message(self, fmt, *args):
        log("%s - %s" % (self.address_string(), fmt % args))


def main() -> None:
    if not TOKEN:
        log("REBOOTER_TOKEN ist nicht gesetzt - Sidecar startet nicht (fail-closed)")
        sys.exit(1)
    server = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    log(f"Rebooter-Sidecar lauscht auf Port {PORT}")
    server.serve_forever()


if __name__ == "__main__":
    main()
