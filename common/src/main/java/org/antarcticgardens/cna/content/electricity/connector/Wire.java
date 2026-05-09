package org.antarcticgardens.cna.content.electricity.connector;

import net.createmod.catnip.data.Pair;
import org.antarcticgardens.cna.config.CNAConfig;
import org.joml.Vector3f;

import java.util.*;

public class Wire {
    public static final float SAG_FACTOR = 0.9f;

    private final List<Pair<WireSection, Float>> sections = new ArrayList<>();
    
    private final Vector3f direction;
    private final Vector3f up;
    private final float sectionLength;
    
    public Wire(Vector3f direction, float distance, int sectionsAmount, Vector3f globalUp) {
        this.direction = direction;
        up = calculateUp(direction, globalUp);
        sectionLength = distance / sectionsAmount;
        
        float catenaryScalar = new Vector3f(up).mul(globalUp).length();
        float lastCatenary = 0.0f;
        for (int i = 1; i <= sectionsAmount; i++) {
            float catenary = catenary(i, distance, sectionsAmount) * catenaryScalar;
            WireSection section = WireSection.getOrCreate(sectionLength, CNAConfig.getClient().wireThickness.get().floatValue(),
                    catenary - lastCatenary, (i % 2 == 0) ? 0.5f : 0.0f);
            lastCatenary = catenary;
            sections.add(Pair.of(section, lastCatenary));
        }
    }

    private Vector3f calculateUp(Vector3f direction, Vector3f dimensionalUp) {
        Vector3f right = new Vector3f(direction).cross(dimensionalUp);
        if (right.equals(new Vector3f(0.0f), 0.01f))
            return new Vector3f(1.0f, 0.0f, 0.0f);
        return right.normalize().cross(direction).normalize();
    }

    private float catenary(double x, double length, int sections) {
        double a = length / CNAConfig.getServer().maxWireLength.get() * SAG_FACTOR;
        x = (x / sections * 2 - 1);
        return (float) ((Math.cosh(x) - Math.cosh(1.0f)) * a);
    }

    public Vector3f getDirection() {
        return new Vector3f(direction);
    }

    public Vector3f getUp() {
        return new Vector3f(up);
    }

    public float getSectionLength() {
        return sectionLength;
    }
    
    public List<Pair<WireSection, Float>> getSections() {
        return Collections.unmodifiableList(sections);
    }
}
