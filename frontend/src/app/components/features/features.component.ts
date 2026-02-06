import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../../shared/components/icon/icon.component';

/**
 * Interface for feature items displayed in the features section
 */
interface Feature {
  iconName: string;
  title: string;
  description: string;
  color: string;
  available: boolean;
}

/**
 * Features component displaying the main functionality of the application.
 * Shows available features and upcoming functionality.
 */
@Component({
  selector: 'app-features',
  standalone: true,
  imports: [CommonModule, IconComponent],
  templateUrl: './features.component.html',
  styleUrl: './features.component.scss'
})
export class FeaturesComponent {
  /** List of application features to display */
  readonly features: Feature[] = [
    {
      iconName: 'bar-chart-3',
      title: 'Zählerstände erfassen',
      description: 'Erfassen Sie einfach und schnell Ihre Strom-, Gas- und Wasserzählerstände. Mit intuitiver Eingabemaske und automatischer Validierung.',
      color: 'blue',
      available: true
    },
    {
      iconName: 'trending-up',
      title: 'Verbrauchsübersicht',
      description: 'Visualisieren Sie Ihren Verbrauch mit übersichtlichen Diagrammen und Statistiken. Erkennen Sie Trends und optimieren Sie Ihren Energieverbrauch.',
      color: 'green',
      available: true
    },
    {
      iconName: 'clipboard-list',
      title: 'Historische Daten',
      description: 'Greifen Sie jederzeit auf Ihre historischen Zählerstände zu. Perfekt für Jahresabrechnungen und Vergleiche über längere Zeiträume.',
      color: 'purple',
      available: true
    },
    {
      iconName: 'dollar-sign',
      title: 'Kostenübersicht',
      description: 'Berechnen Sie automatisch Ihre Kosten basierend auf aktuellen Tarifen. Behalten Sie Ihr Budget im Blick.',
      color: 'orange',
      available: false
    },
    {
      iconName: 'bell',
      title: 'Erinnerungen',
      description: 'Lassen Sie sich automatisch daran erinnern, wenn es Zeit ist, neue Zählerstände zu erfassen.',
      color: 'red',
      available: false
    },
    {
      iconName: 'package',
      title: 'Produktverwaltung',
      description: 'Verwalten Sie Ihre Haushaltsprodukte und Lagerbestände. Erstellen Sie Einkaufslisten und behalten Sie den Überblick.',
      color: 'indigo',
      available: false
    }
  ];
}
