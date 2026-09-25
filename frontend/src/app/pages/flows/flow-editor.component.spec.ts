import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ActivatedRoute } from '@angular/router';
import { of, throwError, Subject } from 'rxjs';
import { FlowEditorComponent } from './flow-editor.component';
import { FlowService } from '../../services/flow.service';
import { EntityStateService } from '../../services/entity-state.service';
import { SmartDeviceService } from '../../services/smart-device.service';
import { fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';

describe('FlowEditorComponent', () => {
  let flowService: jasmine.SpyObj<FlowService>;
  let entityService: jasmine.SpyObj<EntityStateService>;
  let deviceService: jasmine.SpyObj<SmartDeviceService>;

  beforeEach(async () => {
    entityService = jasmine.createSpyObj('EntityStateService', ['getEntities']);
    entityService.getEntities.and.returnValue(of([]));
    deviceService = jasmine.createSpyObj('SmartDeviceService', ['getAllDevices']);
    deviceService.getAllDevices.and.returnValue(of([]));
    flowService = jasmine.createSpyObj('FlowService',
      ['getFlow', 'getFlows', 'getNodeTypes', 'saveDraft', 'deploy', 'setEnabled', 'inject']);
    flowService.getFlow.and.returnValue(of({
      id: 1, name: 'F', category: 'Licht', enabled: false, deployed: false,
      draftDefinition: '{"nodes":[{"id":"n1","type":"entity-state-trigger","position":{"x":0,"y":0},"config":{}}],"wires":[]}'
    } as any));
    flowService.getFlows.and.returnValue(of([
      { id: 1, name: 'F', category: 'Licht', enabled: true, deployed: true },
      { id: 2, name: 'G', category: 'Taster', enabled: true, deployed: true },
      { id: 3, name: 'H', enabled: true, deployed: true }
    ] as any));
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
        { provide: EntityStateService, useValue: entityService },
        { provide: SmartDeviceService, useValue: deviceService },
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

  it('clamps the side panel width to its allowed range', () => {
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(c.clampSideWidth(100)).toBe(220);
    expect(c.clampSideWidth(9000)).toBe(720);
    expect(c.clampSideWidth(400)).toBe(400);
  });

  describe('live status of the canvas nodes', () => {
    const flurMotion = {
      entityId: 'binary_sensor.zigbee_motion_flur_occupancy', domain: 'BINARY_SENSOR', source: 'ZIGBEE',
      sourceRef: 'x', friendlyName: 'Motion Flur', displayName: 'Motion Flur Bewegung', state: 'on',
      attributes: {}, lastChanged: '2026-09-21T19:50:08', lastUpdated: '2026-09-21T19:50:08'
    };

    beforeEach(() => {
      flowService.getFlow.and.returnValue(of({
        id: 1, name: 'F', enabled: false, deployed: false,
        draftDefinition: JSON.stringify({
          nodes: [
            { id: 'trig', type: 'entity-state-trigger', position: { x: 0, y: 0 }, config: { entityId: flurMotion.entityId } },
            { id: 'wait', type: 'delay', position: { x: 0, y: 0 }, config: { seconds: 5 } }
          ],
          wires: []
        })
      } as any));
    });

    it('resolves a status per node with entity or device reference and none for the rest', fakeAsync(() => {
      entityService.getEntities.and.returnValue(of([flurMotion as any]));
      const fixture = TestBed.createComponent(FlowEditorComponent);
      fixture.detectChanges();
      tick();

      const statuses = fixture.componentInstance.statusByNodeId();
      expect(statuses['trig']?.name).toBe('Motion Flur Bewegung');
      expect(statuses['trig']?.state).toBe('on');
      expect(statuses['wait']).toBeUndefined();
      discardPeriodicTasks();
    }));

    it('polls entities and devices every 10 seconds while the editor is open', fakeAsync(() => {
      const fixture = TestBed.createComponent(FlowEditorComponent);
      fixture.detectChanges();
      tick();
      expect(entityService.getEntities).toHaveBeenCalledTimes(1);
      expect(deviceService.getAllDevices).toHaveBeenCalledTimes(1);

      tick(10_000);
      expect(entityService.getEntities).toHaveBeenCalledTimes(2);
      expect(deviceService.getAllDevices).toHaveBeenCalledTimes(2);

      fixture.destroy();
      tick(10_000);
      expect(entityService.getEntities).toHaveBeenCalledTimes(2);
    }));

    it('keeps the last status when a refresh fails', fakeAsync(() => {
      entityService.getEntities.and.returnValue(of([flurMotion as any]));
      const fixture = TestBed.createComponent(FlowEditorComponent);
      fixture.detectChanges();
      tick();
      expect(fixture.componentInstance.statusByNodeId()['trig']?.state).toBe('on');

      entityService.getEntities.and.returnValue(throwError(() => new Error('weg')));
      tick(10_000);
      expect(fixture.componentInstance.statusByNodeId()['trig']?.state).toBe('on');

      // Der Poller lebt nach dem Fehler weiter.
      entityService.getEntities.and.returnValue(of([{ ...flurMotion, state: 'off' } as any]));
      tick(10_000);
      expect(fixture.componentInstance.statusByNodeId()['trig']?.state).toBe('off');
      discardPeriodicTasks();
    }));
  });

  it('loads the category and offers existing categories as suggestions', () => {
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.category()).toBe('Licht');
    expect(fixture.componentInstance.knownCategories()).toEqual(['Licht', 'Taster']);
    const options = Array.from(fixture.nativeElement.querySelectorAll('#flow-categories option'))
      .map(o => (o as HTMLOptionElement).value);
    expect(options).toEqual(['Licht', 'Taster']);
  });

  it('still loads the flow when the category suggestions fail', () => {
    flowService.getFlows.and.returnValue(throwError(() => new Error('down')));
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.canvasNodes().length).toBe(1);
    expect(fixture.componentInstance.knownCategories()).toEqual([]);
  });

  it('marks the flow dirty when only the category or only the name changes', () => {
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    const editor = fixture.componentInstance;

    editor.onCategoryInput('Taster');
    expect(editor.dirty()).toBeTrue();
    editor.onCategoryInput('Licht');
    expect(editor.dirty()).toBeFalse();

    editor.onNameInput('Umbenannt');
    expect(editor.dirty()).toBeTrue();
  });

  it('saves the category and clears the dirty flag', () => {
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    const editor = fixture.componentInstance;

    editor.onCategoryInput('Taster');
    editor.save();

    expect(flowService.saveDraft.calls.mostRecent().args[4]).toBe('Taster');
    expect(editor.dirty()).toBeFalse();
  });

  it('stays dirty when the category is edited again while a save is still in flight', () => {
    const subject = new Subject<any>();
    flowService.saveDraft.and.returnValue(subject);
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    const editor = fixture.componentInstance;

    editor.onCategoryInput('A');
    editor.save();
    editor.onCategoryInput('B');
    subject.next({} as any);
    subject.complete();

    expect(editor.dirty()).toBeTrue();
  });
});
