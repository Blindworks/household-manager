import { RecurringPayment } from '../models/finance.model';
import { merchantLabel, recurringStatusLabel } from './recurring-view.util';

function item(confirmed: boolean, ended: boolean): RecurringPayment {
  return {
    id: 1, accountId: 1, counterpartyPattern: 'X', expectedAmount: -10,
    interval: 'MONTHLY' as RecurringPayment['interval'], confirmed, ended
  };
}

describe('recurring-view.util', () => {
  it('zeigt PayPal-Händler mit Vermittler', () => {
    expect(merchantLabel('PAYPAL:SPOTIFY AB')).toBe('SPOTIFY AB (über PayPal)');
  });

  it('zeigt Amazon-Händler mit Vermittler', () => {
    expect(merchantLabel('AMAZON:FOO')).toBe('FOO (über Amazon)');
    expect(merchantLabel('AMZN:FOO')).toBe('FOO (über Amazon)');
  });

  it('lässt normale Empfänger unverändert', () => {
    expect(merchantLabel('DRILLISCH ONLINE GMBH')).toBe('DRILLISCH ONLINE GMBH');
  });

  it('beendet gewinnt vor bestätigt', () => {
    expect(recurringStatusLabel(item(true, true))).toBe('beendet');
    expect(recurringStatusLabel(item(false, true))).toBe('beendet');
  });

  it('unterscheidet bestätigt und Kandidat', () => {
    expect(recurringStatusLabel(item(true, false))).toBe('bestätigt');
    expect(recurringStatusLabel(item(false, false))).toBe('Kandidat');
  });
});
