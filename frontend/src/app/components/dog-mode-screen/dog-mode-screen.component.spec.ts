import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DogModeScreenComponent } from './dog-mode-screen.component';
import { DogModeClimate } from '../../shared/dog-mode.util';

describe('DogModeScreenComponent', () => {
  let fixture: ComponentFixture<DogModeScreenComponent>;

  const render = (climate: DogModeClimate, extra: { busy?: boolean; error?: string | null } = {}): HTMLElement => {
    fixture.componentRef.setInput('clockTime', '18:42');
    fixture.componentRef.setInput('climate', climate);
    fixture.componentRef.setInput('busy', extra.busy ?? false);
    fixture.componentRef.setInput('error', extra.error ?? null);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [DogModeScreenComponent] }).compileComponents();
    fixture = TestBed.createComponent(DogModeScreenComponent);
  });

  it('zeigt Uhrzeit, Innen- und Aussentemperatur', () => {
    const el = render({ indoor: { label: '22°', stale: false }, outdoorLabel: '14°' });

    expect(el.querySelector('.dog-mode__time')?.textContent?.trim()).toBe('18:42');
    expect(el.querySelector('.dog-mode__indoor-value')?.textContent?.trim()).toBe('22°');
    expect(el.querySelector('.dog-mode__outdoor')?.textContent).toContain('Draußen 14°');
    expect(el.querySelector('.dog-mode__image')?.getAttribute('src')).toBe('assets/dog-mode/dog.svg');
    expect(el.textContent).toContain('Toni ist allein zu Haus');
  });

  it('zeigt einen Strich ohne Innenwert', () => {
    const el = render({ indoor: null, outdoorLabel: '14°' });

    expect(el.querySelector('.dog-mode__indoor-value')?.textContent?.trim()).toBe('–');
  });

  it('dunkelt einen veralteten Innenwert ab', () => {
    const el = render({ indoor: { label: '22°', stale: true }, outdoorLabel: null });

    expect(el.querySelector('.dog-mode__indoor')?.classList).toContain('dog-mode__indoor--stale');
  });

  it('laesst die Aussenzeile ohne Aussenwert weg', () => {
    const el = render({ indoor: { label: '22°', stale: false }, outdoorLabel: null });

    expect(el.querySelector('.dog-mode__outdoor')).toBeNull();
  });

  it('meldet den Knopfdruck ueber endMode', () => {
    const el = render({ indoor: null, outdoorLabel: null });
    let emitted = 0;
    fixture.componentInstance.endMode.subscribe(() => emitted++);

    (el.querySelector('.dog-mode__end') as HTMLButtonElement).click();

    expect(emitted).toBe(1);
  });

  it('sperrt den Knopf waehrend der Aktion', () => {
    const el = render({ indoor: null, outdoorLabel: null }, { busy: true });

    expect((el.querySelector('.dog-mode__end') as HTMLButtonElement).disabled).toBeTrue();
  });

  it('zeigt einen Fehlertext', () => {
    const el = render({ indoor: null, outdoorLabel: null }, { error: 'Toni allein konnte nicht geschaltet werden.' });

    expect(el.querySelector('.dog-mode__error')?.textContent).toContain('konnte nicht geschaltet werden');
  });
});
