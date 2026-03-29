package com.siteplain.support;

import static java.util.Map.entry;

import java.util.Locale;
import java.util.Map;

public final class StateResourceRegistry {

    private static final Map<String, StateResource> RESOURCES = Map.ofEntries(
            entry("AK", new StateResource("Alaska DEC Brownfields", "http://dec.alaska.gov/spar/csp/brownfields/")),
            entry("AL", new StateResource("Alabama Brownfields & VCP", "https://aldem.maps.arcgis.com/apps/MapJournal/index.html?appidce125cd4b4b64fe087e26524a8c0a674")),
            entry("AR", new StateResource("Arkansas DEQ Brownfield Program", "https://www.adeq.state.ar.us/hazwaste/programs/brownfield/")),
            entry("AZ", new StateResource("Arizona DEQ Voluntary Remediation Program", "http://www.azdeq.gov/VRP")),
            entry("CA", new StateResource("California DTSC Brownfields", "https://dtsc.ca.gov/brownfields/")),
            entry("CO", new StateResource("Colorado CDPHE Voluntary Cleanup Program", "https://www.colorado.gov/pacific/cdphe/voluntary-cleanup")),
            entry("CT", new StateResource("Connecticut DEEP Brownfields", "http://www.ct.gov/deep/brownfields")),
            entry("DE", new StateResource("Delaware DNREC Cleanup & Brownfields", "http://www.dnrec.delaware.gov/dwhs/Pages/default.aspx")),
            entry("FL", new StateResource("Florida DEP Brownfields Program", "https://floridadep.gov/waste/waste-cleanup/content/brownfields-program")),
            entry("GA", new StateResource("Georgia EPD Brownfields & Voluntary Remediation", "https://epd.georgia.gov/land-pollution/brownfields-and-voluntary-remediation")),
            entry("HI", new StateResource("Hawaii HEER Brownfields Redevelopment", "https://health.hawaii.gov/heer/about-heer/organization/sdar-programs/brownfields/redevelopment/")),
            entry("IA", new StateResource("Iowa DNR Brownfields", "https://www.iowadnr.gov/Environmental-Protection/Land-Quality/Contaminated-Sites/Brownfields")),
            entry("ID", new StateResource("Idaho DEQ Brownfields", "http://www.deq.idaho.gov/waste-mgmt-remediation/brownfields.aspx")),
            entry("IL", new StateResource("Illinois EPA Brownfields Program", "https://epa.illinois.gov/topics/cleanup-programs/brownfields.html")),
            entry("IN", new StateResource("Indiana Brownfields Program", "http://www.in.gov/ifa/brownfields/")),
            entry("KS", new StateResource("Kansas KDHE Redevelopment Programs", "https://www.kdhe.ks.gov/478/Redevelopment")),
            entry("KY", new StateResource("Kentucky Brownfield Redevelopment Program", "http://dep.ky.gov/Pages/brownfields.aspx")),
            entry("LA", new StateResource("Louisiana DEQ Brownfields", "http://www.deq.la.gov/brownfields")),
            entry("MA", new StateResource("Massachusetts Cleanup of Sites & Spills", "https://www.mass.gov/topics/cleanup-of-sites-spills")),
            entry("MD", new StateResource("Maryland Department of the Environment", "http://www.mde.Maryland.gov/Pages/Home.aspx")),
            entry("ME", new StateResource("Maine DEP Brownfields", "https://www.maine.gov/dep/spills/brownfields/")),
            entry("MI", new StateResource("Michigan EGLE Brownfields", "https://www.michigan.gov/egle/0,9429,7-135-3306_28608---,00.html")),
            entry("MN", new StateResource("Minnesota Brownfield Redevelopment", "https://www.pca.state.mn.us/business-with-us/brownfield-redevelopment")),
            entry("MO", new StateResource("Missouri Brownfields / Voluntary Cleanup", "http://www.MissouriBrownfields.com")),
            entry("MS", new StateResource("Mississippi Brownfield Program", "http://www.brownfields.ms")),
            entry("MT", new StateResource("Montana DEQ Brownfields", "https://deq.mt.gov/cleanupandrec/Programs/brownfields")),
            entry("NC", new StateResource("North Carolina Waste Management", "https://deq.nc.gov/about/divisions/waste-management")),
            entry("ND", new StateResource("North Dakota DEQ Brownfields", "https://deq.nd.gov/WM/Brownfields/")),
            entry("NE", new StateResource("Nebraska Voluntary Cleanup Programs", "https://dee.nebraska.gov/land-waste/voluntary-cleanup-programs")),
            entry("NH", new StateResource("New Hampshire DES Brownfields", "https://www.des.nh.gov/waste/contaminated-sites/brownfields")),
            entry("NJ", new StateResource("New Jersey DEP Brownfields", "http://www.nj.gov/dep/srp/brownfields/")),
            entry("NM", new StateResource("New Mexico Environment Department", "https://www.env.nm.gov/")),
            entry("NV", new StateResource("Nevada Division of Environmental Protection VCP", "http://ndep.nv.gov/bca/vcp.htm")),
            entry("NY", new StateResource("New York DEC Brownfield Cleanup Program", "https://www.dec.ny.gov/chemical/8439.html")),
            entry("OH", new StateResource("Ohio EPA Voluntary Action Program", "https://epa.ohio.gov/divisions-and-offices/environmental-response-revitalization/derr-programs/voluntary-action-program")),
            entry("OK", new StateResource("Oklahoma OCC Brownfields", "https://oklahoma.gov/occ/divisions/oil-gas/pollution-abatement-department/brownfields.html")),
            entry("OR", new StateResource("Oregon DEQ Environmental Cleanup", "http://www.oregon.gov/deq/Hazards-and-Cleanup/env-cleanup/Pages/default.aspx")),
            entry("PA", new StateResource("Pennsylvania DEP Land Recycling", "http://www.dep.pa.gov/")),
            entry("RI", new StateResource("Rhode Island DEM Site Remediation", "https://dem.ri.gov/environmental-protection-bureau/land-revitalization-and-sustainable-materials-management/site-remediation-program")),
            entry("SC", new StateResource("South Carolina Brownfields / VCP", "http://www.scdhec.gov/HomeAndEnvironment/Pollution/CleanUpPrograms/BrownfieldsCleanupLoanFund/")),
            entry("SD", new StateResource("South Dakota DANR Brownfields", "https://danr.sd.gov/Agriculture/Inspection/Brownfields/default.aspx")),
            entry("TN", new StateResource("Tennessee Brownfields Redevelopment", "https://www.tn.gov/environment/program-areas/rem-remediation/rem-brownfields-redevelopment-overview.html")),
            entry("TX", new StateResource("Texas Commission on Environmental Quality (TCEQ)", "https://www.tceq.texas.gov/remediation")),
            entry("UT", new StateResource("Utah DEQ Voluntary Cleanup Program", "https://deq.utah.gov/environmental-response-and-remediation/cercla-comprehensive-environmental-response-compensation-and-liability-act/voluntary-cleanup-program")),
            entry("VA", new StateResource("Virginia DEQ Remediation Program", "http://www.deq.virginia.gov/Programs/LandProtectionRevitalization/RemediationProgram.aspx")),
            entry("VT", new StateResource("Vermont DEC BRELLA", "http://dec.vermont.gov/waste-management/contaminated-sites/brownfields/BRELLA")),
            entry("WA", new StateResource("Washington Brownfields Program", "https://ecology.wa.gov/Brownfields")),
            entry("WI", new StateResource("Wisconsin DNR Brownfields", "http://dnr.wi.gov/topic/Brownfields/")),
            entry("WV", new StateResource("West Virginia Office of Environmental Remediation", "https://dep.wv.gov/dlr/oer/Pages/default.aspx")),
            entry("WY", new StateResource("Wyoming DEQ Brownfields Assistance", "http://deq.wyoming.gov/shwd/brownfields-assistance/"))
    );

    private StateResourceRegistry() {
    }

    public static StateResource lookup(String stateCode) {
        if (stateCode == null || stateCode.isBlank()) {
            return null;
        }
        return RESOURCES.get(stateCode.trim().toUpperCase(Locale.ROOT));
    }

    public record StateResource(
            String name,
            String url
    ) {
    }
}
