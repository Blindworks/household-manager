import { TestBed } from '@angular/core/testing';
import { NodePaletteComponent } from './node-palette.component';
import { NodeType } from '../../models/flow.model';

describe('NodePaletteComponent', () => {
  const types: NodeType[] = [
    { type: 'entity-state-trigger', trigger: true, outputPorts: 1, portLabels: ['Ausgang'], fields: [] },
    { type: 'entity-condition', trigger: false, outputPorts: 2, portLabels: ['wahr', 'falsch'], fields: [] },
    { type: 'alexa-announce', trigger: false, outputPorts: 1, portLabels: ['Ausgang'], fields: [] }
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [NodePaletteComponent] }).compileComponents();
  });

  it('groups node types into trigger / logic / action', () => {
    const fixture = TestBed.createComponent(NodePaletteComponent);
    fixture.componentRef.setInput('nodeTypes', types);
    fixture.detectChanges();
    expect(fixture.componentInstance.triggers().length).toBe(1);
    expect(fixture.componentInstance.actions().length).toBe(1);
    expect(fixture.componentInstance.logic().length).toBe(1);
  });

  it('emits add when a palette item is activated', () => {
    const fixture = TestBed.createComponent(NodePaletteComponent);
    fixture.componentRef.setInput('nodeTypes', types);
    fixture.detectChanges();
    let added: string | undefined;
    fixture.componentInstance.add.subscribe((t: string) => (added = t));
    fixture.componentInstance.onAdd('alexa-announce');
    expect(added).toBe('alexa-announce');
  });
});
