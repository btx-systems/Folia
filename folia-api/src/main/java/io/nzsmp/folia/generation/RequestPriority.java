package io.nzsmp.folia.generation;

/**
 * Ordered from highest to lowest urgency.
 */
public enum RequestPriority {

    /**
     * blocking urgency.
     */
    BLOCKING,
    
    /**
     * highest urgency.
     */
    HIGHEST,
    
    /**
     * high urgency.
     */
    HIGH,
    
    /**
     * normal urgency.
     */
    NORMAL,
    
    /**
     * low urgency.
     */
    LOW,
    
    /**
     * lowest urgency.
     */
    LOWEST,
    
    /**
     * idle urgency.
     */
    IDLE
}
