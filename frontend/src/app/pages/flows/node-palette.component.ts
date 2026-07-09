import { Component, EventEmitter, Output, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NodeType } from '../../models/flow.model';

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

  private readonly actionTypes = new Set(['alexa-announce', 'switch-device']);

  readonly triggers = computed(() => this.nodeTypes().filter(t => t.trigger));
  readonly actions = computed(() => this.nodeTypes().filter(t => !t.trigger && this.actionTypes.has(t.type)));
  readonly logic = computed(() => this.nodeTypes().filter(t => !t.trigger && !this.actionTypes.has(t.type)));

  onAdd(type: string): void {
    this.add.emit(type);
  }
}
