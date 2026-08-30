/**
 * Ein fertiger Hinweis fuer den Intelligence Hub. Strukturgleich zum dortigen
 * `IntelligenceItem`: Das Dashboard rendert die Meldung selbst, weil die Styles der
 * Hub-Eintraege in seinem eigenen SCSS liegen und Angulars Style-Kapselung sie nicht
 * an eine Kind-Komponente weiterreicht.
 */
export interface HubInsight {
  readonly icon: string;
  readonly tone: 'primary' | 'secondary' | 'muted' | 'tertiary' | 'error';
  readonly title: string;
  readonly text: string;
  /**
   * Entity-ID eines manuellen Helfers, den ein Antippen der Karte ausschaltet.
   * Nur Karten mit diesem Feld sind antippbar; alle uebrigen Hub-Karten sind
   * reine Anzeige.
   */
  readonly dismissEntityId?: string;
}
