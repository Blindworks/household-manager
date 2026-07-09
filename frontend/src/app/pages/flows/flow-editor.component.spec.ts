import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
import { FlowEditorComponent } from './flow-editor.component';
import { FlowService } from '../../services/flow.service';

describe('FlowEditorComponent', () => {
  let flowService: jasmine.SpyObj<FlowService>;

  beforeEach(async () => {
    flowService = jasmine.createSpyObj('FlowService',
      ['getFlow', 'getNodeTypes', 'saveDraft', 'deploy', 'setEnabled', 'inject']);
    flowService.getFlow.and.returnValue(of({
      id: 1, name: 'F', enabled: false, deployed: false,
      draftDefinition: '{"nodes":[{"id":"n1","type":"entity-state-trigger","position":{"x":0,"y":0},"config":{}}],"wires":[]}'
    } as any));
    flowService.getNodeTypes.and.returnValue(of([
      { type: 'entity-state-trigger', trigger: true, outputPorts: 1, portLabels: ['Ausgang'], fields: [] }
    ] as any));
    flowService.saveDraft.and.returnValue(of({} as any));
    flowService.deploy.and.returnValue(of({ errors: [], warnings: [] }));
    await TestBed.configureTestingModule({
      imports: [FlowEditorComponent],
      providers: [
        provideRouter([]),
        { provide: FlowService, useValue: flowService },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => '1' } } } }
      ]
    }).compileComponents();
  });

  it('loads flow and node types on init', () => {
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.canvasNodes().length).toBe(1);
    expect(fixture.componentInstance.nodeTypes().length).toBe(1);
  });

  it('adds a node from the palette', () => {
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    fixture.componentInstance.addNode('entity-state-trigger');
    expect(fixture.componentInstance.canvasNodes().length).toBe(2);
    expect(fixture.componentInstance.dirty()).toBe(true);
  });

  it('saves draft as our JSON format', () => {
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    fixture.componentInstance.save();
    expect(flowService.saveDraft).toHaveBeenCalled();
    const draftArg = flowService.saveDraft.calls.mostRecent().args[3];
    expect(JSON.parse(draftArg).nodes[0].id).toBe('n1');
  });

  it('deploy shows returned warnings/errors', () => {
    flowService.deploy.and.returnValue(of({ errors: ['x'], warnings: [] }));
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    fixture.componentInstance.deploy();
    expect(fixture.componentInstance.deployErrors()).toEqual(['x']);
  });
});
