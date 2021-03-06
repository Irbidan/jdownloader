package jd.plugins.components.youtube;

public enum MediaTagsVarious implements MediaQualityInterface {

    SUBTITLE(1, 10),
    VIDEO_FPS_60(5, 100);

    private double rating = -1;

    private MediaTagsVarious(double rating, double modifier) {
        this.rating = rating / modifier;
    }

    public void setRating(double rating) {
        this.rating = rating;
    }

    public double getRating() {
        return rating;
    }
}
