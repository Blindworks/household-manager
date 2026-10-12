import {
  ACTION_CLASS_LABELS,
  NOTE_KIND_LABELS,
  POLICY_LABELS,
  PROPOSAL_STATUS_LABELS,
  sourceLabel
} from './agent-labels.util';

describe('agent-labels.util', () => {
  it('benennt Klassen, Freigaben, Status und Notizarten auf Deutsch', () => {
    expect(ACTION_CLASS_LABELS.LOCK).toBe('Haustür verriegeln');
    expect(ACTION_CLASS_LABELS.SWITCH).toBe('Schalter & Licht');
    expect(POLICY_LABELS.DIRECT).toBe('direkt ausführen');
    expect(PROPOSAL_STATUS_LABELS.EXECUTED).toBe('Ausgeführt');
    expect(NOTE_KIND_LABELS.PREFERENCE).toBe('Vorliebe');
  });

  it('übersetzt Quellen und lässt unbekannte unverändert', () => {
    expect(sourceLabel('AGENT')).toBe('Hausverstand');
    expect(sourceLabel('USER')).toBe('Haushalt');
    expect(sourceLabel('IRGENDWAS')).toBe('IRGENDWAS');
  });
});
