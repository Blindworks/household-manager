import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { IconComponent } from './icon.component';

@Component({
  standalone: true,
  imports: [IconComponent],
  template: `
    <app-icon name="sun"></app-icon>
    <svg class="foreign" viewBox="0 0 10 10"><text x="0" y="8">A</text></svg>
  `
})
class HostComponent {}

describe('IconComponent', () => {
  it('gibt den eigenen Icons eine Kontur', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();

    const iconSvg: SVGElement = fixture.nativeElement.querySelector('app-icon svg');
    expect(getComputedStyle(iconSvg).strokeWidth).toBe('2px');
  });

  it('laesst fremde SVGs der Seite in Ruhe', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();

    const foreignText: SVGElement = fixture.nativeElement.querySelector('svg.foreign text');
    expect(getComputedStyle(foreignText).stroke).toBe('none');
  });
});
