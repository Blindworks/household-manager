import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, of, throwError } from 'rxjs';
import { LaundryPromptComponent } from './laundry-prompt.component';
import { LaundryService } from '../../services/laundry.service';
import { LaundryState } from '../../models/laundry.model';

function state(prompt: LaundryState['prompt']): LaundryState {
  return {
    status: prompt === 'NONE' ? 'drying' : 'asking',
    prompt,
    startedAt: null,
    predictedDryAt: null,
    estimatePaused: false,
    requiredLoad: 0
  };
}

describe('LaundryPromptComponent', () => {
  let service: jasmine.SpyObj<LaundryService>;
  let fixture: ComponentFixture<LaundryPromptComponent>;

  function create(initial: LaundryState): HTMLElement {
    service.getState.and.returnValue(of(initial));
    fixture = TestBed.createComponent(LaundryPromptComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function buttons(root: HTMLElement): HTMLButtonElement[] {
    return Array.from(root.querySelectorAll<HTMLButtonElement>('button'));
  }

  beforeEach(() => {
    service = jasmine.createSpyObj('LaundryService', ['getState', 'answerHung', 'answerDry', 'markRemoved']);
    TestBed.configureTestingModule({
      imports: [LaundryPromptComponent],
      providers: [{ provide: LaundryService, useValue: service }]
    });
  });

  it('zeigtFrageAufgehaengt', () => {
    const root = create(state('HUNG'));
    expect(root.querySelector('h2')!.textContent).toContain('Wäsche aufgehängt?');
    expect(buttons(root).map(b => b.textContent!.trim())).toEqual(['Ja', 'Nein']);
  });

  it('zeigtFrageTrockenMitNochNicht', () => {
    const root = create(state('DRY'));
    expect(root.querySelector('h2')!.textContent).toContain('Ist die Wäsche trocken?');
    expect(buttons(root).map(b => b.textContent!.trim())).toEqual(['Ja', 'Noch nicht']);
  });

  it('ohneFrageNichtsSichtbar', () => {
    const root = create(state('NONE'));
    expect(root.querySelector('.laundry-prompt')).toBeNull();
    expect(buttons(root).length).toBe(0);
  });

  it('jaSendetAnswerHungTrue', () => {
    service.answerHung.and.returnValue(of(state('NONE')));
    const root = create(state('HUNG'));
    const emitted: LaundryState[] = [];
    fixture.componentInstance.answered.subscribe(s => emitted.push(s));
    buttons(root)[0].click();
    fixture.detectChanges();
    expect(service.answerHung).toHaveBeenCalledWith(true);
    expect(emitted.length).toBe(1);
    expect(root.querySelector('.laundry-prompt')).toBeNull();
  });

  it('neinSendetAnswerHungFalse', () => {
    service.answerHung.and.returnValue(of(state('NONE')));
    const root = create(state('HUNG'));
    buttons(root)[1].click();
    expect(service.answerHung).toHaveBeenCalledWith(false);
  });

  it('trockenAntwortenGehenAnAnswerDry', () => {
    service.answerDry.and.returnValue(of(state('NONE')));
    const root = create(state('DRY'));
    buttons(root)[1].click();
    expect(service.answerDry).toHaveBeenCalledWith(false);
  });

  it('knoepfeGesperrtWaehrendAnfrage', () => {
    const pending = new Subject<LaundryState>();
    service.answerHung.and.returnValue(pending);
    const root = create(state('HUNG'));
    buttons(root)[0].click();
    fixture.detectChanges();
    expect(buttons(root).every(b => b.disabled)).toBeTrue();
    buttons(root)[0].click();
    expect(service.answerHung).toHaveBeenCalledTimes(1);
  });

  it('konfliktLaedtZustandNeu', () => {
    service.answerHung.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409 })));
    const root = create(state('HUNG'));
    service.getState.and.returnValue(of(state('NONE')));
    buttons(root)[0].click();
    fixture.detectChanges();
    expect(service.getState).toHaveBeenCalledTimes(2);
    expect(root.querySelector('.laundry-prompt')).toBeNull();
  });
});
