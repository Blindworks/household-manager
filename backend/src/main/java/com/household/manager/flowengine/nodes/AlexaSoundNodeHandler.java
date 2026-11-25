package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.service.AlexaAnnouncementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * Aktions-Node: kurzer Alexa-Ton (Sound-ID aus Amazons Soundbibliothek) auf mehreren Geräten
 * gleichzeitig, gedacht als sofortige Rückmeldung auf einen Knopfdruck. Keine Sprachausgabe,
 * ein einziger Aufruf an Amazon.
 *
 * <p>Der Ton wird abgeschickt, ohne auf Amazon zu warten: die Nachricht geht sofort weiter, damit
 * ein Ton nie das Verriegeln oder eine andere nachgelagerte Node verzögert. Folge: scheitert der
 * Ton, erscheint das nur als WARN im Log/Systemprotokoll, nicht als ERROR des Flow-Laufs.
 */
@Component
@Slf4j
public class AlexaSoundNodeHandler implements NodeHandler {

    /** Dieselbe Regel wie im Sidecar ({@code alexa-sidecar/sound.js}). */
    private static final Pattern SOUND_ID = Pattern.compile("[A-Za-z0-9_:/.\\-]{1,200}");

    private final AlexaAnnouncementService announcementService;
    private final Executor soundExecutor;

    public AlexaSoundNodeHandler(AlexaAnnouncementService announcementService,
                                 @Qualifier("alexaSoundExecutor") Executor soundExecutor) {
        this.announcementService = announcementService;
        this.soundExecutor = soundExecutor;
    }

    @Override
    public String type() {
        return "alexa-sound";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        String sound = config.string("sound").orElse(null);
        if (sound == null) {
            errors.add("sound fehlt");
        } else if (!SOUND_ID.matcher(sound).matches()) {
            errors.add("sound ist keine gültige Sound-ID");
        }
        if (config.stringList("deviceSerials").isEmpty()) {
            errors.add("deviceSerials fehlt oder ist leer");
        }
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        String sound = config.string("sound").orElse("");
        List<String> serials = config.stringList("deviceSerials");
        try {
            soundExecutor.execute(() -> play(sound, serials));
        } catch (RejectedExecutionException ex) {
            log.warn("Alexa-Ton {} verworfen: zu viele Töne gleichzeitig", sound);
            if (ctx != null) {
                ctx.reportFailure("Alexa-Ton verworfen: zu viele Töne gleichzeitig");
            }
        }
        return NodeResult.single(message);
    }

    private void play(String sound, List<String> serials) {
        try {
            announcementService.playSound(sound, serials);
        } catch (RuntimeException ex) {
            log.warn("Alexa-Ton {} fehlgeschlagen: {}", sound, ex.getMessage());
        }
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("sound", "Sound-ID (z. B. amzn_sfx_doorbell_chime_01)", NodeFieldType.STRING, true),
                NodeFieldDescriptor.field("deviceSerials", "Alexa-Geräte", NodeFieldType.ALEXA_DEVICE_LIST, true));
    }
}
