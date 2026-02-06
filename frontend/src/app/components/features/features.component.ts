import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Interface for feature items displayed in the features section
 */
interface Feature {
  icon: string;
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
  imports: [CommonModule],
  templateUrl: './features.component.html',
  styleUrl: './features.component.scss'
})
export class FeaturesComponent {
  /** List of application features to display */
  readonly features: Feature[] = [
    {
      icon: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z',
      title: 'Zählerstände erfassen',
      description: 'Erfassen Sie einfach und schnell Ihre Strom-, Gas- und Wasserzählerstände. Mit intuitiver Eingabemaske und automatischer Validierung.',
      color: 'blue',
      available: true
    },
    {
      icon: 'M7 12l3-3 3 3 4-4M8 21l4-4 4 4M3 4h18M4 4h16v12a1 1 0 01-1 1H5a1 1 0 01-1-1V4z',
      title: 'Verbrauchsübersicht',
      description: 'Visualisieren Sie Ihren Verbrauch mit übersichtlichen Diagrammen und Statistiken. Erkennen Sie Trends und optimieren Sie Ihren Energieverbrauch.',
      color: 'green',
      available: true
    },
    {
      icon: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-3 7h3m-3 4h3m-6-4h.01M9 16h.01',
      title: 'Historische Daten',
      description: 'Greifen Sie jederzeit auf Ihre historischen Zählerstände zu. Perfekt für Jahresabrechnungen und Vergleiche über längere Zeiträume.',
      color: 'purple',
      available: true
    },
    {
      icon: 'M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z',
      title: 'Kostenübersicht',
      description: 'Berechnen Sie automatisch Ihre Kosten basierend auf aktuellen Tarifen. Behalten Sie Ihr Budget im Blick.',
      color: 'orange',
      available: false
    },
    {
      icon: 'M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9',
      title: 'Erinnerungen',
      description: 'Lassen Sie sich automatisch daran erinnern, wenn es Zeit ist, neue Zählerstände zu erfassen.',
      color: 'red',
      available: false
    },
    {
      icon: 'M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z',
      title: 'Produktverwaltung',
      description: 'Verwalten Sie Ihre Haushaltsprodukte und Lagerbestände. Erstellen Sie Einkaufslisten und behalten Sie den Überblick.',
      color: 'indigo',
      available: false
    }
  ];
}
