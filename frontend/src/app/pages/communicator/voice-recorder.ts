/**
 * Duenne Huelle um `MediaRecorder`: eine Aufnahme starten, beim Stoppen den Ton als Blob liefern.
 *
 * Bewusst keine Browser-Spracherkennung (`SpeechRecognition`): in der zum Home-Bildschirm
 * hinzugefuegten PWA auf iOS ist sie unzuverlaessig. Aufnehmen und auf dem Server umwandeln
 * funktioniert dagegen ueberall, wo es ein Mikrofon gibt.
 */
export class VoiceRecorder {
  /** iOS nimmt nur MP4/AAC auf, Chrome und Firefox WebM bzw. Ogg; der erste unterstuetzte Typ gewinnt. */
  private static readonly MIME_TYPES = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg'];

  private recorder: MediaRecorder | null = null;
  private stream: MediaStream | null = null;
  private chunks: Blob[] = [];
  private cancelled = false;

  static isSupported(): boolean {
    return typeof MediaRecorder !== 'undefined' && !!navigator.mediaDevices?.getUserMedia;
  }

  get recording(): boolean {
    return this.recorder?.state === 'recording';
  }

  /**
   * Fragt beim ersten Mal die Mikrofon-Berechtigung an; ein Nein kommt als abgelehntes Promise.
   * `ready` läuft, sobald das Mikrofon bereit ist, und vor der Aufnahme: der Signalton soll nicht mit aufs Band.
   *
   * @returns false, wenn inzwischen abgebrochen wurde
   */
  async start(ready: () => Promise<void> = () => Promise.resolve()): Promise<boolean> {
    this.stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    if (!this.cancelled) {
      await ready();
    }
    if (this.cancelled) {
      this.release();
      return false;
    }
    const mimeType = VoiceRecorder.MIME_TYPES.find(type => MediaRecorder.isTypeSupported(type));
    this.chunks = [];
    this.recorder = new MediaRecorder(this.stream, mimeType ? { mimeType } : undefined);
    this.recorder.ondataavailable = event => {
      if (event.data.size > 0) {
        this.chunks.push(event.data);
      }
    };
    this.recorder.start();
    return true;
  }

  /** Beendet die Aufnahme und gibt das Mikrofon frei; `null`, wenn nichts aufgenommen wurde. */
  stop(): Promise<Blob | null> {
    const recorder = this.recorder;
    if (!recorder || recorder.state === 'inactive') {
      this.release();
      return Promise.resolve(null);
    }
    return new Promise(resolve => {
      recorder.onstop = () => {
        const blob = this.chunks.length
          ? new Blob(this.chunks, { type: recorder.mimeType || this.chunks[0].type })
          : null;
        this.release();
        resolve(blob);
      };
      recorder.stop();
    });
  }

  /** Verwirft eine laufende Aufnahme (Seite wird verlassen). */
  cancel(): void {
    this.cancelled = true;
    if (this.recorder && this.recorder.state !== 'inactive') {
      this.recorder.onstop = null;
      this.recorder.stop();
    }
    this.release();
  }

  private release(): void {
    this.stream?.getTracks().forEach(track => track.stop());
    this.stream = null;
    this.recorder = null;
    this.chunks = [];
  }
}
