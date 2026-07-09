import { CanDeactivateFn } from '@angular/router';
import { FlowEditorComponent } from './flow-editor.component';

/** Fragt bei ungespeicherten Änderungen nach, bevor der Flow-Editor verlassen wird. */
export const unsavedChangesGuard: CanDeactivateFn<FlowEditorComponent> = (component) => {
  return component.dirty() ? confirm('Ungespeicherte Änderungen verwerfen?') : true;
};
