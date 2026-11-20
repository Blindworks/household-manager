import {
  countByVerdict, filterJudgements, formatRunDuration, formatTokens, judgementOfferMeta, productNormalPrice,
  productNote, reasonKindLabel, runStatusView, triggerLabel
} from './grocery-run-log.util';
import { GroceryRunJudgementLine, GroceryRunLogSummary, GroceryRunProductLine } from '../models/grocery.model';

function run(over: Partial<GroceryRunLogSummary> = {}): GroceryRunLogSummary {
  return {
    id: 1, trigger: 'NIGHTLY', startedAt: '2026-11-20T04:00:00', finishedAt: '2026-11-20T04:05:12', ok: true,
    error: null, offers: 500, rawOffers: 600, products: 8, aiCalls: 3, estimateCalls: 1, inputTokens: 12000,
    outputTokens: 900, aiFailures: 0, deferred: 0, newJudgements: 40, deals: 2, sourceFailures: 0, ...over
  };
}

function judgement(over: Partial<GroceryRunJudgementLine> = {}): GroceryRunJudgementLine {
  return {
    productId: 1, productName: 'Cola', verdict: 'MATCH', reasonKind: null, reason: null,
    offerTitle: 'Cola Zero 1,5 l', chain: 'Penny', price: 0.86, basePrice: 0.573, baseUnit: 'LITER', ...over
  };
}

function product(over: Partial<GroceryRunProductLine> = {}): GroceryRunProductLine {
  return {
    productId: 1, name: 'Cola', candidates: 5, unjudged: 2, newJudgements: 2, aiCalls: 1, aiFailures: 0,
    deferred: false, error: null, matches: 1, maybes: 1, deals: 1, normalPrice: null, estimatedPrice: 1.99,
    baseUnit: 'LITER', ...over
  };
}

describe('grocery-run-log.util', () => {
  it('runStatusView: OK, Hinweise und Abbruch', () => {
    expect(runStatusView(run())).toEqual({ tone: 'ok', text: 'OK' });
    expect(runStatusView(run({ sourceFailures: 2, aiFailures: 1, deferred: 3 }))).toEqual({
      tone: 'warn', text: '2 Quellen gestört, 1 Claude-Fehler, 3 Produkte verschoben'
    });
    expect(runStatusView(run({ sourceFailures: 1 })).text).toBe('1 Quelle gestört');
    expect(runStatusView(run({ ok: false, error: 'Netz weg' }))).toEqual({ tone: 'error', text: 'Abgebrochen: Netz weg' });
  });

  it('triggerLabel', () => {
    expect(triggerLabel(run())).toBe('Nachtlauf');
    expect(triggerLabel(run({ trigger: 'MANUAL' }))).toBe('Von Hand');
  });

  it('formatRunDuration', () => {
    expect(formatRunDuration('2026-11-20T04:00:00', '2026-11-20T04:05:12')).toBe('5 Min. 12 s');
    expect(formatRunDuration('2026-11-20T04:00:00', '2026-11-20T04:00:48')).toBe('48 s');
    expect(formatRunDuration('kaputt', '2026-11-20T04:00:48')).toBe('–');
  });

  it('formatTokens', () => {
    expect(formatTokens(12345)).toBe('12.345');
    expect(formatTokens(1_250_000)).toBe('1,3 Mio.');
  });

  it('filterJudgements und countByVerdict', () => {
    const list = [judgement(), judgement({ verdict: 'NO', productId: 2 }), judgement({ verdict: 'MAYBE' })];
    expect(filterJudgements(list, 'ALL', null).length).toBe(3);
    expect(filterJudgements(list, 'NO', null).map(j => j.productId)).toEqual([2]);
    expect(filterJudgements(list, 'ALL', 1).length).toBe(2);
    expect(countByVerdict(list)).toEqual({ MATCH: 1, MAYBE: 1, NO: 1 });
  });

  it('judgementOfferMeta: Angebot und abgelaufenes Angebot', () => {
    expect(judgementOfferMeta(judgement())).toBe('Penny · 0,86 € · 0,57 €/l');
    expect(judgementOfferMeta(judgement({ offerTitle: null }))).toBe('Angebot nicht mehr im Bestand');
  });

  it('reasonKindLabel', () => {
    expect(reasonKindLabel('PACKAGING_DIFFERS')).toBe('Packung weicht ab');
    expect(reasonKindLabel(null)).toBeNull();
    expect(reasonKindLabel('NEU')).toBe('NEU');
  });

  it('productNormalPrice und productNote', () => {
    expect(productNormalPrice(product())).toBe('1,99 €/l (geschätzt)');
    expect(productNormalPrice(product({ normalPrice: 1.5 }))).toBe('1,50 €/l (eigener)');
    expect(productNormalPrice(product({ estimatedPrice: null }))).toBe('–');
    expect(productNote(product())).toBe('');
    expect(productNote(product({ aiFailures: 1, deferred: true })))
      .toBe('1 Claude-Aufruf(e) ohne Antwort, verschoben (Claude-Deckel)');
    expect(productNote(product({ error: 'DB weg' }))).toBe('Fehler: DB weg');
  });
});
