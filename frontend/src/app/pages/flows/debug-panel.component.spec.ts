import { TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { of } from 'rxjs';
import { DebugPanelComponent } from './debug-panel.component';
import { FlowService } from '../../services/flow.service';

describe('DebugPanelComponent', () => {
  let flowService: jasmine.SpyObj<FlowService>;

  beforeEach(async () => {
    flowService = jasmine.createSpyObj('FlowService', ['getDebug']);
    flowService.getDebug.and.returnValue(of([
      { timestamp: '2026-07-09T12:00:00', label: 'x', message: { v: 1 } }
    ] as any));
    await TestBed.configureTestingModule({
      imports: [DebugPanelComponent],
      providers: [{ provide: FlowService, useValue: flowService }]
    }).compileComponents();
  });

  it('polls debug of debug-nodes when active and deployed', fakeAsync(() => {
    const fixture = TestBed.createComponent(DebugPanelComponent);
    fixture.componentRef.setInput('flowId', 1);
    fixture.componentRef.setInput('deployed', true);
    fixture.componentRef.setInput('active', true);
    fixture.componentRef.setInput('nodes', [{ id: 'd1', type: 'debug', x: 0, y: 0, config: {} }]);
    fixture.detectChanges();
    tick(0);
    expect(flowService.getDebug).toHaveBeenCalledWith(1, 'd1');
    expect(fixture.componentInstance.entries().length).toBe(1);
    tick(2000);
    expect(flowService.getDebug).toHaveBeenCalledTimes(2);
    discardPeriodicTasks();
  }));

  it('does not poll when not deployed', fakeAsync(() => {
    const fixture = TestBed.createComponent(DebugPanelComponent);
    fixture.componentRef.setInput('flowId', 1);
    fixture.componentRef.setInput('deployed', false);
    fixture.componentRef.setInput('active', true);
    fixture.componentRef.setInput('nodes', [{ id: 'd1', type: 'debug', x: 0, y: 0, config: {} }]);
    fixture.detectChanges();
    tick(3000);
    expect(flowService.getDebug).not.toHaveBeenCalled();
    discardPeriodicTasks();
  }));
});
