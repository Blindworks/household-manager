import { ALL_KINDS, ALL_STATUSES, PROBLEM_STATUSES, kindIcon, statusLabel, statusTone, stepOutcomeLabel } from './system-log-view.util';

describe('system-log-view.util', () => {
  it('has a label and tone for every status', () => {
    expect(ALL_STATUSES.length).toBe(10);
    ALL_STATUSES.forEach(s => {
      expect(statusLabel(s)).toBeTruthy();
      expect(statusTone(s)).toBeTruthy();
    });
    expect(statusLabel('STOPPED')).toBe('an Bedingung gestoppt');
    expect(statusLabel('RECOVERED')).toBe('wieder ok');
  });

  it('has an icon for every kind', () => {
    ALL_KINDS.forEach(k => expect(kindIcon(k)).toBeTruthy());
  });

  it('PROBLEM_STATUSES contains exactly the six problem statuses', () => {
    expect([...PROBLEM_STATUSES].sort())
      .toEqual(['ABORTED', 'ERROR', 'MISSED', 'RATE_LIMITED', 'STOPPED', 'WARN']);
  });

  it('labels step outcomes', () => {
    expect(stepOutcomeLabel('port:1')).toContain('Ausgang 1');
    expect(stepOutcomeLabel('stopped')).toBe('gestoppt');
    expect(stepOutcomeLabel('whatever')).toBe('whatever');
  });
});
