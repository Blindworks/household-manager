const START_HZ = [660, 880];
const STOP_HZ = [880, 660];
const NOTE_SECONDS = 0.09;
const VOLUME = 0.2;

/**
 * Kurze Signaltöne ohne Audiodatei: aufsteigend = jetzt sprechen, absteigend = Aufnahme beendet.
 * Wirft nie und wartet nie länger als der Ton dauert; ohne Ton läuft die Aufnahme trotzdem.
 */
export class SignalTones {
  private context: AudioContext | null = null;

  /** Muss direkt aus dem Tipp kommen: iOS gibt Ton nur nach einer Nutzer-Geste frei. */
  unlock(): void {
    try {
      this.context ??= new AudioContext();
      void this.context.resume().catch(() => undefined);
    } catch {
      this.context = null;
    }
  }

  start(): Promise<void> {
    return this.play(START_HZ);
  }

  stop(): Promise<void> {
    return this.play(STOP_HZ);
  }

  close(): void {
    void this.context?.close().catch(() => undefined);
    this.context = null;
  }

  private play(notes: number[]): Promise<void> {
    const context = this.context;
    if (!context || context.state === 'closed') {
      return Promise.resolve();
    }
    try {
      // Das Mikrofon kann den Ton auf iOS angehalten haben.
      void context.resume().catch(() => undefined);
      notes.forEach((frequency, i) => this.note(context, frequency, context.currentTime + i * NOTE_SECONDS));
    } catch {
      return Promise.resolve();
    }
    return new Promise(resolve => setTimeout(resolve, notes.length * NOTE_SECONDS * 1000 + 40));
  }

  private note(context: AudioContext, frequency: number, at: number): void {
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    oscillator.frequency.value = frequency;
    // Ein- und ausblenden, sonst knackt es.
    gain.gain.setValueAtTime(0, at);
    gain.gain.linearRampToValueAtTime(VOLUME, at + 0.01);
    gain.gain.linearRampToValueAtTime(0, at + NOTE_SECONDS);
    oscillator.connect(gain).connect(context.destination);
    oscillator.start(at);
    oscillator.stop(at + NOTE_SECONDS);
  }
}
