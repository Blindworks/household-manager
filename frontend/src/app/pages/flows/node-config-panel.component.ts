import { Component, EventEmitter, Output, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NodeType, NodeFieldDescriptor } from '../../models/flow.model';
import { CanvasNode } from './flow-graph.mapper';
import { EntityPickerComponent } from './pickers/entity-picker.component';
import { DevicePickerComponent } from './pickers/device-picker.component';
import { AlexaDevicePickerComponent } from './pickers/alexa-device-picker.component';

/** Schema-getriebenes Konfig-Formular der ausgewählten Node. */
@Component({
  selector: 'app-node-config-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, EntityPickerComponent, DevicePickerComponent, AlexaDevicePickerComponent],
  templateUrl: './node-config-panel.component.html',
  styleUrl: './node-config-panel.component.scss'
})
export class NodeConfigPanelComponent {
  readonly node = input<CanvasNode>();
  readonly nodeType = input<NodeType>();

  @Output() configChange = new EventEmitter<Record<string, unknown>>();

  readonly fields = computed<NodeFieldDescriptor[]>(() => this.nodeType()?.fields ?? []);

  isRequired(key: string): boolean {
    return this.fields().find(f => f.key === key)?.required ?? false;
  }

  fieldValue(key: string): unknown {
    return this.node()?.config?.[key];
  }

  setField(key: string, value: unknown): void {
    const current = { ...(this.node()?.config ?? {}) };
    current[key] = value;
    this.configChange.emit(current);
  }
}
