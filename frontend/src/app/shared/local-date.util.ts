const pad = (value: number): string => String(value).padStart(2, '0');

/** Lokales Datum als YYYY-MM-DD (nicht UTC wie toISOString). */
export function toLocalDateString(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** Datum (YYYY-MM-DD) mit der lokalen Uhrzeit von `time`, als YYYY-MM-DDTHH:mm:ss. */
export function toLocalDateTimeString(dateString: string, time: Date): string {
  return `${dateString}T${pad(time.getHours())}:${pad(time.getMinutes())}:${pad(time.getSeconds())}`;
}
