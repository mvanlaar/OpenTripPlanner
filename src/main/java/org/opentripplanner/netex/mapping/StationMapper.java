package org.opentripplanner.netex.mapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.opentripplanner.graph_builder.DataImportIssueStore;
import org.opentripplanner.netex.mapping.support.FeedScopedIdFactory;
import org.opentripplanner.transit.model.basic.I18NString;
import org.opentripplanner.transit.model.basic.NonLocalizedString;
import org.opentripplanner.transit.model.basic.TranslatedString;
import org.opentripplanner.transit.model.basic.WgsCoordinate;
import org.opentripplanner.transit.model.site.Station;
import org.rutebanken.netex.model.MultilingualString;
import org.rutebanken.netex.model.NameTypeEnumeration;
import org.rutebanken.netex.model.Quay;
import org.rutebanken.netex.model.StopPlace;

class StationMapper {

  private final DataImportIssueStore issueStore;

  private final FeedScopedIdFactory idFactory;

  StationMapper(DataImportIssueStore issueStore, FeedScopedIdFactory idFactory) {
    this.issueStore = issueStore;
    this.idFactory = idFactory;
  }

  Station map(StopPlace stopPlace) {
    return Station
      .of(idFactory.createId(stopPlace.getId()))
      .withName(resolveName(stopPlace))
      .withCoordinate(mapCoordinate(stopPlace))
      .withDescription(
        NonLocalizedString.ofNullable(stopPlace.getDescription(), MultilingualString::getValue)
      )
      .withPriority(StopTransferPriorityMapper.mapToDomain(stopPlace.getWeighting()))
      .build();
  }

  private I18NString resolveName(StopPlace stopPlace) {
    final I18NString name;
    if (stopPlace.getName() == null) {
      name = new NonLocalizedString("N/A");
    } else if (stopPlace.getAlternativeNames() != null) {
      Map<String, String> translations = new HashMap<>();
      translations.put(null, stopPlace.getName().getValue());
      for (var translation : stopPlace.getAlternativeNames().getAlternativeName()) {
        if (translation.getNameType() == NameTypeEnumeration.TRANSLATION) {
          String lang = translation.getLang() != null
            ? translation.getLang()
            : translation.getName().getLang();
          translations.put(lang, translation.getName().getValue());
        }
      }

      name = TranslatedString.getI18NString(translations, true, false);
    } else {
      name = new NonLocalizedString(stopPlace.getName().getValue());
    }
    return name;
  }

  /**
   * Map the centroid to coordinate, if not present the mean coordinate for the
   * child quays is returned. If the station do not have any quays an exception is thrown.
   */
  private WgsCoordinate mapCoordinate(StopPlace stopPlace) {
    if (stopPlace.getCentroid() != null) {
      return WgsCoordinateMapper.mapToDomain(stopPlace.getCentroid());
    } else {
      issueStore.add(
        "StationWithoutCoordinates",
        "Station %s does not contain any coordinates.",
        stopPlace.getId() + " " + stopPlace.getName()
      );
      List<WgsCoordinate> coordinates = new ArrayList<>();
      for (Object it : stopPlace.getQuays().getQuayRefOrQuay()) {
        if (it instanceof Quay quay) {
          coordinates.add(WgsCoordinateMapper.mapToDomain(quay.getCentroid()));
        }
      }
      if (coordinates.isEmpty()) {
        throw new IllegalArgumentException(
          "Station w/quays without coordinates. Station id: " + stopPlace.getId()
        );
      }
      return WgsCoordinate.mean(coordinates);
    }
  }
}
