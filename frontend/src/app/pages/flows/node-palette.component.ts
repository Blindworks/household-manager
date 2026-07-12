import { Component, EventEmitter, Output, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NodeType } from '../../models/flow.model';
import { nodeCategory, nodeLabel } from './node-catalog';

/** Palette der verfügbaren Node-Typen, gruppiert; per Klick hinzufügbar. */
@Component({
  selector: 'app-node-palette',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './node-palette.component.html',
  styleUrl: './node-palette.component.scss'
})
export class NodePaletteComponent {
  readonly nodeTypes = input<NodeType[]>([]);
  @Output() add = new EventEmitter<string>();

  readonly triggers = computed(() => this.nodeTypes().filter(t => nodeCategory(t.type, t.trigger) === 'trigger'));
  readonly actions = computed(() => this.nodeTypes().filter(t => nodeCategory(t.type, t.trigger) === 'action'));
  readonly logic = computed(() => this.nodeTypes().filter(t => nodeCategory(t.type, t.trigger) === 'logic'));

  label(type: string): string {
    return nodeLabel(type);
  }

  onAdd(type: string): void {
    this.add.emit(type);
  }
}
