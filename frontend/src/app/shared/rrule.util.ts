/** Vom Builder abgedeckte Wochentage/Frequenzen (Teilmenge von RFC 5545). */
export type Weekday = 'MO' | 'TU' | 'WE' | 'TH' | 'FR' | 'SA' | 'SU';
export type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';

/** Zustand des Wiederholungs-Builders im Termindialog. */
export interface RecurrenceOptions {
  freq: Frequency;
  /** >= 1; "alle 2 Wochen" = 2. */
  interval: number;
  /** Nur bei WEEKLY. */
  weekdays: Weekday[];
  /** Nur bei MONTHLY: am Monatstag des Starts oder am n-ten Wochentag des Starts. */
  monthlyMode: 'DAY_OF_MONTH' | 'NTH_WEEKDAY';
  endType: 'NEVER' | 'UNTIL' | 'COUNT';
  /** ISO-Datum, bei endType UNTIL. */
  untilDate: string | null;
  /** Bei endType COUNT. */
  count: number | null;
}

/** Index = Date.getDay() (0 = Sonntag). */
const WEEKDAY_CODES: Weekday[] = ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'];

/** Baut aus den Builder-Optionen die RRULE; NTH_WEEKDAY leitet sich vom Startdatum ab. */
export function buildRrule(options: RecurrenceOptions, startDate: string): string {
  const parts = [`FREQ=${options.freq}`];
  if (options.interval > 1) {
    parts.push(`INTERVAL=${options.interval}`);
  }
  if (options.freq === 'WEEKLY' && options.weekdays.length > 0) {
    parts.push(`BYDAY=${options.weekdays.join(',')}`);
  }
  if (options.freq === 'MONTHLY') {
    const [year, month, day] = startDate.split('-').map(Number);
    if (options.monthlyMode === 'DAY_OF_MONTH') {
      parts.push(`BYMONTHDAY=${day}`);
    } else {
      const nth = Math.floor((day - 1) / 7) + 1;
      const weekday = WEEKDAY_CODES[new Date(year, month - 1, day).getDay()];
      // Ein "5." Vorkommen (Tage 29.-31.) gibt es in den meisten Monaten nicht — die Regel
      // wuerde dann fast immer ausfallen. Die iCal-Negativform "letzter Wochentag" trifft
      // dagegen in jedem Monat; fuer 1.-4. bleibt die Zaehlung "der n-te" wie gewaehlt.
      const ordinal = nth === 5 ? '-1' : `${nth}`;
      parts.push(`BYDAY=${ordinal}${weekday}`);
    }
  }
  if (options.endType === 'UNTIL') {
    if (!options.untilDate) {
      // Nutzertext statt einer technischen Meldung: dieser Fehler ist ueber ganz normale
      // Bedienschritte erreichbar (Dialog waehlt "Ende: Am Datum", Feld ist noch leer) und
      // muss dem Nutzer verstaendlich im Dialog angezeigt werden koennen (siehe Aufrufer).
      throw new Error('Bitte ein Enddatum fuer die Wiederholung angeben.');
    }
    parts.push(`UNTIL=${options.untilDate.replaceAll('-', '')}`);
  }
  if (options.endType === 'COUNT') {
    if (options.count === null || options.count < 1) {
      // Truthiness wuerde count=0 (und negative Werte) stillschweigend verwerfen — die
      // Regel liefe dann unendlich statt "null mal". Lieber laut scheitern als das.
      throw new Error('Bitte eine Anzahl von mindestens 1 fuer die Wiederholung angeben.');
    }
    parts.push(`COUNT=${options.count}`);
  }
  return parts.join(';');
}

/**
 * Uebersetzt eine RRULE zurueck in Builder-Optionen.
 *
 * @returns null, wenn die Regel Features nutzt, die der Builder nicht abbildet —
 *          der Dialog zeigt sie dann nur im "Erweitert"-Modus als Rohtext.
 */
export function parseRrule(rrule: string): RecurrenceOptions | null {
  const entries = new Map<string, string>();
  for (const part of rrule.split(';').filter(p => p.length > 0)) {
    const [key, value] = part.split('=');
    if (!key || value === undefined) {
      return null;
    }
    entries.set(key.toUpperCase(), value);
  }

  const freq = entries.get('FREQ') as Frequency | undefined;
  if (!freq || !['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'].includes(freq)) {
    return null;
  }
  // WKST (Wochenstart) taucht bei praktisch jedem von Google/Outlook exportierten
  // Weekly-Termin auf; es aendert die Bedeutung nur bei INTERVAL>1 mit mehreren
  // Wochentagen, fuer alles vom Baukasten Erzeugte ist es folgenlos — daher nur
  // akzeptieren und ignorieren, statt die ganze Regel deswegen abzulehnen.
  const supported = new Set(['FREQ', 'INTERVAL', 'BYDAY', 'BYMONTHDAY', 'UNTIL', 'COUNT', 'WKST']);
  if ([...entries.keys()].some(key => !supported.has(key))) {
    return null;
  }

  const result: RecurrenceOptions = {
    freq,
    interval: entries.has('INTERVAL') ? Number(entries.get('INTERVAL')) : 1,
    weekdays: [],
    monthlyMode: 'DAY_OF_MONTH',
    endType: 'NEVER',
    untilDate: null,
    count: null
  };
  if (Number.isNaN(result.interval) || result.interval < 1) {
    return null;
  }

  const byday = entries.get('BYDAY');
  if (byday !== undefined) {
    if (freq === 'WEEKLY') {
      const days = byday.split(',');
      if (!days.every(d => WEEKDAY_CODES.includes(d as Weekday))) {
        return null;
      }
      result.weekdays = days as Weekday[];
    } else if (freq === 'MONTHLY' && /^(-1|[1-5])(MO|TU|WE|TH|FR|SA|SU)$/.test(byday)) {
      result.monthlyMode = 'NTH_WEEKDAY';
    } else {
      return null;
    }
  }
  if (entries.has('BYMONTHDAY') && (freq !== 'MONTHLY' || byday !== undefined)) {
    return null;
  }

  const until = entries.get('UNTIL');
  const count = entries.get('COUNT');
  if (until !== undefined && count !== undefined) {
    return null;
  }
  if (until !== undefined) {
    if (!/^\d{8}$/.test(until)) {
      return null;
    }
    result.endType = 'UNTIL';
    result.untilDate = `${until.slice(0, 4)}-${until.slice(4, 6)}-${until.slice(6, 8)}`;
  }
  if (count !== undefined) {
    result.endType = 'COUNT';
    result.count = Number(count);
    if (Number.isNaN(result.count) || result.count < 1) {
      return null;
    }
  }
  return result;
}
