/** Begruessung nach Tageszeit (Haushaltszeit = Geraeteuhr), einzige Definition fuer den Communicator. */
export function greetingFor(date: Date, name?: string | null): string {
  const hour = date.getHours();
  const base = hour < 5 ? 'Gute Nacht'
    : hour < 11 ? 'Guten Morgen'
    : hour < 18 ? 'Hallo'
    : hour < 22 ? 'Guten Abend'
    : 'Gute Nacht';
  const first = name?.trim().split(/\s+/)[0];
  return first ? `${base}, ${first}` : base;
}

/** „Mittwoch, 25. November“ */
export function formatDayLine(date: Date): string {
  return date.toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'long' });
}

/** Startfragen, die als Knoepfe unter der Begruessung stehen. Reine Textfragen, kein Feature-Code. */
export const QUICK_QUESTIONS: readonly { icon: string; text: string }[] = [
  { icon: 'thermostat', text: 'Wie warm ist es im Haus?' },
  { icon: 'door_front', text: 'Ist die Haustür abgeschlossen?' },
  { icon: 'bolt', text: 'Was verbraucht gerade Strom?' },
  { icon: 'delete', text: 'Wann kommt die Müllabfuhr?' }
];
