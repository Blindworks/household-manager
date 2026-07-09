import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { FlowListComponent } from './flow-list.component';
import { FlowService } from '../../services/flow.service';

describe('FlowListComponent', () => {
  let flowService: jasmine.SpyObj<FlowService>;

  beforeEach(async () => {
    flowService = jasmine.createSpyObj('FlowService', ['getFlows', 'createFlow', 'deleteFlow', 'setEnabled']);
    flowService.getFlows.and.returnValue(of([
      { id: 1, name: 'A', enabled: true, deployed: true },
      { id: 2, name: 'B', enabled: false, deployed: false }
    ] as any));
    await TestBed.configureTestingModule({
      imports: [FlowListComponent],
      providers: [provideRouter([]), { provide: FlowService, useValue: flowService }]
    }).compileComponents();
  });

  it('loads flows on init', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.flows().length).toBe(2);
  });

  it('deletes a flow and reloads', () => {
    flowService.deleteFlow.and.returnValue(of(void 0));
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();
    fixture.componentInstance.deleteFlow({ id: 1, name: 'A', enabled: true, deployed: true } as any);
    expect(flowService.deleteFlow).toHaveBeenCalledWith(1);
    expect(flowService.getFlows).toHaveBeenCalledTimes(2);
  });
});
