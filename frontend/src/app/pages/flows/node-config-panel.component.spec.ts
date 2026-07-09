import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NodeConfigPanelComponent } from './node-config-panel.component';
import { NodeType } from '../../models/flow.model';
import { CanvasNode } from './flow-graph.mapper';

describe('NodeConfigPanelComponent', () => {
  const nodeType: NodeType = {
    type: 'entity-state-trigger', trigger: true, outputPorts: 1, portLabels: ['Ausgang'],
    fields: [
      { key: 'entityId', label: 'Entity', type: 'ENTITY_REF', required: true, options: [] },
      { key: 'operator', label: 'Operator', type: 'ENUM', required: true, options: ['<', '>'] },
      { key: 'forSeconds', label: 'seit', type: 'NUMBER', required: false, options: [] }
    ]
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NodeConfigPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
  });

  function make() {
    const fixture = TestBed.createComponent(NodeConfigPanelComponent);
    fixture.componentRef.setInput('node', { id: 'n1', type: 'entity-state-trigger', x: 0, y: 0, config: {} } as CanvasNode);
    fixture.componentRef.setInput('nodeType', nodeType);
    fixture.detectChanges();
    return fixture;
  }

  it('renders one row per field of the node type', () => {
    const fixture = make();
    expect(fixture.nativeElement.querySelectorAll('.config-field').length).toBe(3);
  });

  it('emits configChange when a value is set', () => {
    const fixture = make();
    let emitted: any;
    fixture.componentInstance.configChange.subscribe((c: any) => (emitted = c));
    fixture.componentInstance.setField('operator', '<');
    expect(emitted.operator).toBe('<');
  });

  it('marks required fields', () => {
    const fixture = make();
    expect(fixture.componentInstance.isRequired('entityId')).toBe(true);
    expect(fixture.componentInstance.isRequired('forSeconds')).toBe(false);
  });

  it('shows unknown-type hint when nodeType is missing', () => {
    const fixture = TestBed.createComponent(NodeConfigPanelComponent);
    fixture.componentRef.setInput('node', { id: 'x', type: 'mystery', x: 0, y: 0, config: {} } as CanvasNode);
    fixture.componentRef.setInput('nodeType', undefined);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Unbekannter Typ');
  });
});
