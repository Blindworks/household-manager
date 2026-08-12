import { VentilationAssessment } from '../models/ventilation.model';
import { HubInsight } from './hub-insight.model';

/**
 * Baut aus der Backend-Bewertung die Hub-Sammelkarte, z. B.
 * "Draußen 21° — kühler als Schlafzimmer (26°), Wohnzimmer (25°)".
 *
 * @returns `null`, wenn keine Empfehlung besteht oder keine Aussage moeglich ist
 * (`recommended` null) — dann erscheint im Hub keine Karte. Auch eine
 * widerspruechliche Antwort ohne Aussenwert oder ohne Raeume liefert `null`,
 * statt eine kaputte Karte zu rendern.
 */
export function buildVentilationInsight(
  assessment: VentilationAssessment | null): HubInsight | null {
  if (!assessment?.recommended
      || assessment.outdoorTemperature === null
      || assessment.rooms.length === 0) {
    return null;
  }
  const rooms = assessment.rooms
    .map(room => `${room.name} (${Math.round(room.temperature)}°)`)
    .join(', ');
  return {
    icon: 'air',
    tone: 'secondary',
    title: 'Lüften lohnt sich',
    text: `Draußen ${Math.round(assessment.outdoorTemperature)}° — kühler als ${rooms}`
  };
}
