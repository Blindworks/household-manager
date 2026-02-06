import { MeterType } from '../models/meter-reading.model';

/**
 * Hilfsklasse für Zählertyp-spezifische Operationen und Darstellung
 */
export class MeterTypeUtils {
  /**
   * Gibt das deutsche Label für einen Zählertyp zurück
   */
  static getLabel(type: MeterType): string {
    switch (type) {
      case MeterType.ELECTRICITY:
        return 'Strom';
      case MeterType.GAS:
        return 'Gas';
      case MeterType.WATER:
        return 'Wasser';
      default:
        return 'Unbekannt';
    }
  }

  /**
   * Gibt den Lucide Icon-Namen für einen Zählertyp zurück
   */
  static getIconName(type: MeterType): string {
    switch (type) {
      case MeterType.ELECTRICITY:
        return 'zap';
      case MeterType.GAS:
        return 'flame';
      case MeterType.WATER:
        return 'droplet';
      default:
        return 'gauge';
    }
  }

  /**
   * Alias für getIconName() - gibt den Icon-Namen zurück
   */
  static getIcon(type: MeterType): string {
    return this.getIconName(type);
  }

  /**
   * Gibt die Farbe für einen Zählertyp zurück
   */
  static getColor(type: MeterType): string {
    switch (type) {
      case MeterType.ELECTRICITY:
        return '#f59e0b'; // Orange
      case MeterType.GAS:
        return '#3b82f6'; // Blau
      case MeterType.WATER:
        return '#10b981'; // Grün
      default:
        return '#6b7280'; // Grau
    }
  }

  /**
   * Gibt die Einheit für einen Zählertyp zurück
   */
  static getUnit(type: MeterType): string {
    switch (type) {
      case MeterType.ELECTRICITY:
        return 'kWh';
      case MeterType.GAS:
        return 'kWh';
      case MeterType.WATER:
        return 'm³';
      default:
        return '';
    }
  }

  /**
   * Gibt alle verfügbaren Zählertypen zurück
   */
  static getAllTypes(): MeterType[] {
    return [MeterType.ELECTRICITY, MeterType.GAS, MeterType.WATER];
  }

  /**
   * Konvertiert einen String zu einem MeterType (mit Validierung)
   */
  static fromString(value: string): MeterType | null {
    const upperValue = value.toUpperCase();
    if (Object.values(MeterType).includes(upperValue as MeterType)) {
      return upperValue as MeterType;
    }
    return null;
  }
}
