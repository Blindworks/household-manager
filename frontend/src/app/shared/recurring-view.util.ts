import { RecurringPayment } from '../models/finance.model';

const INTERMEDIARY_LABELS: Record<string, string> = {
  PAYPAL: 'PayPal',
  AMAZON: 'Amazon',
  AMZN: 'Amazon'
};

/** Zeigt den Händlerschlüssel lesbar an ("PAYPAL:X" => "X (über PayPal)"). */
export function merchantLabel(pattern: string): string {
  const separator = pattern.indexOf(':');
  if (separator > 0) {
    const intermediary = INTERMEDIARY_LABELS[pattern.substring(0, separator)];
    if (intermediary) {
      return `${pattern.substring(separator + 1)} (über ${intermediary})`;
    }
  }
  return pattern;
}

export function recurringStatusLabel(item: RecurringPayment): string {
  if (item.ended) {
    return 'beendet';
  }
  return item.confirmed ? 'bestätigt' : 'Kandidat';
}
