/*
 * This library is part of OpenCms -
 * the Open Source Content Management System
 *
 * Copyright (c) Alkacon Software GmbH (http://www.alkacon.com)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * For further information about Alkacon Software, please see the
 * company website: http://www.alkacon.com
 *
 * For further information about OpenCms, please see the
 * project website: http://www.opencms.org
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.opencms.staticexport;

import org.opencms.ade.detailpage.I_CmsDetailPageFinder;
import org.opencms.file.CmsObject;
import org.opencms.file.CmsResource;
import org.opencms.file.CmsResourceFilter;
import org.opencms.file.CmsVfsException;
import org.opencms.file.CmsVfsResourceNotFoundException;
import org.opencms.file.types.CmsResourceTypeImage;
import org.opencms.loader.CmsLoaderException;
import org.opencms.main.CmsException;
import org.opencms.main.CmsLog;
import org.opencms.main.OpenCms;
import org.opencms.site.CmsSite;
import org.opencms.site.CmsSiteMatcher;
import org.opencms.util.CmsFileUtil;
import org.opencms.util.CmsStringUtil;
import org.opencms.util.CmsUUID;
import org.opencms.workplace.CmsWorkplace;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import org.apache.commons.logging.Log;

/**
 * Default link substitution behavior.<p>
 * 
 * @since 7.0.2
 * 
 * @see CmsLinkManager#substituteLink(org.opencms.file.CmsObject, String, String, boolean) 
 *      for the method where this handler is used.
 */
public class CmsDefaultLinkSubstitutionHandler implements I_CmsLinkSubstitutionHandler {

    /** Key for a request context attribute to control whether the getRootPath method uses the current site root for workplace requests.
     *  The getRootPath method clears this attribute when called. 
     */
    public static final String DONT_USE_CURRENT_SITE_FOR_WORKPLACE_REQUESTS = "DONT_USE_CURRENT_SITE_FOR_WORKPLACE_REQUESTS";

    /** The log object for this class. */
    private static final Log LOG = CmsLog.getLog(CmsDefaultLinkSubstitutionHandler.class);

    /**
     * Returns the resource root path in the OpenCms VFS for the given link, or <code>null</code> in
     * case the link points to an external site.<p>
     * 
     * If the target URI contains no site information, but starts with the opencms context, the context is removed:<pre>
     * /opencms/opencms/system/further_path -> /system/further_path</pre>
     * 
     * If the target URI contains no site information, the path will be prefixed with the current site
     * from the provided OpenCms user context:<pre>
     * /folder/page.html -> /sites/mysite/folder/page.html</pre>
     *  
     * If the path of the target URI is relative, i.e. does not start with "/", 
     * the path will be prefixed with the current site and the given relative path,
     * then normalized.
     * If no relative path is given, <code>null</code> is returned.
     * If the normalized path is outsite a site, null is returned.<pre>
     * page.html -> /sites/mysite/page.html
     * ../page.html -> /sites/mysite/page.html
     * ../../page.html -> null</pre>
     * 
     * If the target URI contains a scheme/server name that denotes an opencms site, 
     * it is replaced by the appropriate site path:<pre>
     * http://www.mysite.de/folder/page.html -> /sites/mysite/folder/page.html</pre><p>
     * 
     * If the target URI contains a scheme/server name that does not match with any site, 
     * or if the URI is opaque or invalid,
     * <code>null</code> is returned:<pre>
     * http://www.elsewhere.com/page.html -> null
     * mailto:someone@elsewhere.com -> null</pre>
     * 
     * @see org.opencms.staticexport.I_CmsLinkSubstitutionHandler#getLink(org.opencms.file.CmsObject, java.lang.String, java.lang.String, boolean)
     */
    public String getLink(CmsObject cms, String link, String siteRoot, boolean forceSecure) {

        if (CmsStringUtil.isEmpty(link)) {
            // not a valid link parameter, return an empty String
            return "";
        }
        // make sure we have an absolute link        
        String absoluteLink = CmsLinkManager.getAbsoluteUri(link, cms.getRequestContext().getUri());

        String vfsName;
        String parameters;
        // check if the link has parameters, if so cut them
        int pos = absoluteLink.indexOf('?');
        if (pos >= 0) {
            vfsName = absoluteLink.substring(0, pos);
            parameters = absoluteLink.substring(pos);
        } else {
            vfsName = absoluteLink;
            parameters = null;
        }

        // check for anchor
        String anchor = null;
        pos = vfsName.indexOf('#');
        if (pos >= 0) {
            anchor = vfsName.substring(pos);
            vfsName = vfsName.substring(0, pos);
        }

        String resultLink = null;
        String uriBaseName = null;
        boolean useRelativeLinks = false;

        // determine the target site of the link        
        CmsSite currentSite = OpenCms.getSiteManager().getCurrentSite(cms);
        CmsSite targetSite = null;
        if (CmsStringUtil.isNotEmpty(siteRoot)) {
            targetSite = OpenCms.getSiteManager().getSiteForSiteRoot(siteRoot);
        }
        if (targetSite == null) {
            targetSite = currentSite;
        }

        String targetSiteRoot = targetSite.getSiteRoot();
        String originalVfsName = vfsName;
        String detailPage = null;
        CmsResource detailContent = null;
        try {
            String rootVfsName;
            if (!vfsName.startsWith(targetSiteRoot)
                && !vfsName.startsWith(CmsResource.VFS_FOLDER_SYSTEM + "/")
                && !OpenCms.getSiteManager().startsWithShared(vfsName)) {
                rootVfsName = CmsStringUtil.joinPaths(targetSiteRoot, vfsName);
            } else {
                rootVfsName = vfsName;
            }
            if (!rootVfsName.startsWith(CmsWorkplace.VFS_PATH_WORKPLACE)) {
                // never use the ADE manager for workplace links, to be sure the workplace stays usable in case of configuration errors
                I_CmsDetailPageFinder finder = OpenCms.getADEManager().getDetailPageFinder();
                detailPage = finder.getDetailPage(cms, rootVfsName, cms.getRequestContext().getUri());
            }
            if (detailPage != null) {
                if (detailPage.startsWith(targetSiteRoot)) {
                    detailPage = detailPage.substring(targetSiteRoot.length());
                    if (!detailPage.startsWith("/")) {
                        detailPage = "/" + detailPage;
                    }
                }
                try {
                    CmsResource element = cms.readResource(vfsName);
                    detailContent = element;
                    Locale locale = cms.getRequestContext().getLocale();
                    List<Locale> defaultLocales = OpenCms.getLocaleManager().getDefaultLocales();
                    vfsName = CmsStringUtil.joinPaths(
                        detailPage,
                        cms.getDetailName(element, locale, defaultLocales),
                        "/");
                } catch (CmsVfsException e) {
                    LOG.error(e.getLocalizedMessage(), e);
                }
            }
        } catch (CmsVfsResourceNotFoundException e) {
            LOG.info(e.getLocalizedMessage(), e);
        } catch (CmsException e) {
            LOG.error(e.getLocalizedMessage(), e);
        }

        // if the link points to another site, there needs to be a server prefix
        String serverPrefix;
        if (targetSite != currentSite) {
            serverPrefix = targetSite.getUrl();
        } else {
            serverPrefix = "";
        }

        // in the online project, check static export and secure settings
        if (cms.getRequestContext().getCurrentProject().isOnlineProject()) {
            // first check if this link needs static export
            CmsStaticExportManager exportManager = OpenCms.getStaticExportManager();
            String oriUri = cms.getRequestContext().getUri();
            // check if we need relative links in the exported pages
            if (exportManager.relativeLinksInExport(cms.getRequestContext().getSiteRoot() + oriUri)) {
                // try to get base URI from cache  
                String cacheKey = exportManager.getCacheKey(targetSiteRoot, oriUri);
                uriBaseName = exportManager.getCachedOnlineLink(cacheKey);
                if (uriBaseName == null) {
                    // base not cached, check if we must export it
                    if (exportManager.isExportLink(cms, oriUri)) {
                        // base URI must also be exported
                        uriBaseName = exportManager.getRfsName(cms, oriUri);
                    } else {
                        // base URI dosn't need to be exported
                        uriBaseName = exportManager.getVfsPrefix() + oriUri;
                    }
                    // cache export base URI
                    exportManager.cacheOnlineLink(cacheKey, uriBaseName);
                }
                // use relative links only on pages that get exported
                useRelativeLinks = uriBaseName.startsWith(OpenCms.getStaticExportManager().getRfsPrefix(
                    cms.getRequestContext().getSiteRoot() + oriUri));
            }

            String detailPagePart = detailPage == null ? "" : detailPage + ":";
            // check if we have the absolute VFS name for the link target cached
            // (We really need the target site root in the cache key, because different resources with the same site paths
            // but in different sites may have different export settings. It seems we don't really need the site root 
            // from the request context as part of the key, but we'll leave it in to make sure we don't break anything.)
            String cacheKey = cms.getRequestContext().getSiteRoot()
                + ":"
                + targetSiteRoot
                + ":"
                + detailPagePart
                + absoluteLink;
            resultLink = exportManager.getCachedOnlineLink(cacheKey);
            if (resultLink == null) {
                String storedSiteRoot = cms.getRequestContext().getSiteRoot();
                try {
                    cms.getRequestContext().setSiteRoot(targetSite.getSiteRoot());
                    // didn't find the link in the cache
                    if (exportManager.isExportLink(cms, vfsName)) {
                        // export required, get export name for target link
                        resultLink = exportManager.getRfsName(cms, vfsName, parameters);
                        // now set the parameters to null, we do not need them anymore
                        parameters = null;
                    } else {
                        // no export required for the target link
                        resultLink = exportManager.getVfsPrefix().concat(vfsName);
                        // add cut off parameters if required
                        if (parameters != null) {
                            resultLink = resultLink.concat(parameters);
                        }
                    }
                } finally {
                    cms.getRequestContext().setSiteRoot(storedSiteRoot);
                }
                // cache the result
                exportManager.cacheOnlineLink(cacheKey, resultLink);
            }

            // now check for the secure settings 

            // check if either the current site or the target site does have a secure server configured
            if (targetSite.hasSecureServer() || currentSite.hasSecureServer()) {

                if (!vfsName.startsWith(CmsWorkplace.VFS_PATH_SYSTEM)
                    && !OpenCms.getSiteManager().startsWithShared(vfsName)) {
                    // don't make a secure connection to the "/system" folder (why ?)
                    int linkType = -1;
                    try {
                        // read the linked resource 
                        linkType = cms.readResource(originalVfsName).getTypeId();
                    } catch (CmsException e) {
                        // the resource could not be read
                        if (LOG.isInfoEnabled()) {
                            String message = Messages.get().getBundle().key(
                                Messages.LOG_RESOURCE_ACESS_ERROR_3,
                                vfsName,
                                cms.getRequestContext().getCurrentUser().getName(),
                                cms.getRequestContext().getSiteRoot());
                            if (LOG.isDebugEnabled()) {
                                LOG.debug(message, e);
                            } else {
                                LOG.info(message);
                            }
                        }
                    }

                    // images are always referenced without a server prefix
                    int imageId;
                    try {
                        imageId = OpenCms.getResourceManager().getResourceType(CmsResourceTypeImage.getStaticTypeName()).getTypeId();
                    } catch (CmsLoaderException e1) {
                        // should really never happen
                        LOG.warn(e1.getLocalizedMessage(), e1);
                        imageId = CmsResourceTypeImage.getStaticTypeId();
                    }
                    if (linkType != imageId) {
                        // check the secure property of the link
                        boolean secureRequest = exportManager.isSecureLink(cms, oriUri);

                        boolean secureLink;
                        if (detailContent == null) {
                            secureLink = exportManager.isSecureLink(
                                cms,
                                vfsName,
                                targetSite.getSiteRoot(),
                                secureRequest);
                        } else {
                            secureLink = isDetailPageLinkSecure(
                                cms,
                                detailPage,
                                detailContent,
                                targetSite,
                                secureRequest);

                        }
                        // if we are on a normal server, and the requested resource is secure, 
                        // the server name has to be prepended                        
                        if (secureLink && (forceSecure || !secureRequest)) {
                            serverPrefix = targetSite.getSecureUrl();
                        } else if (!secureLink && secureRequest) {
                            serverPrefix = targetSite.getUrl();
                        }
                    }
                }
            }
            // make absolute link relative, if relative links in export are required
            // and if the link does not point to another server
            if (useRelativeLinks && CmsStringUtil.isEmpty(serverPrefix)) {
                // in case the current page is a detailpage, append another path level
                if (cms.getRequestContext().getDetailContentId() != null) {
                    uriBaseName = CmsStringUtil.joinPaths(
                        CmsResource.getFolderPath(uriBaseName),
                        cms.getRequestContext().getDetailContentId().toString() + "/index.html");
                }
                resultLink = CmsLinkManager.getRelativeUri(uriBaseName, resultLink);
            }

        } else {
            // offline project, no export or secure handling required
            if (OpenCms.getRunLevel() >= OpenCms.RUNLEVEL_3_SHELL_ACCESS) {
                // in unit test this code would fail otherwise
                resultLink = OpenCms.getStaticExportManager().getVfsPrefix().concat(vfsName);
            }

            // add cut off parameters and return the result
            if ((parameters != null) && (resultLink != null)) {
                resultLink = resultLink.concat(parameters);
            }
        }

        if ((anchor != null) && (resultLink != null)) {
            resultLink = resultLink.concat(anchor);
        }

        return serverPrefix.concat(resultLink);
    }

    /**
     * @see org.opencms.staticexport.I_CmsLinkSubstitutionHandler#getRootPath(org.opencms.file.CmsObject, java.lang.String, java.lang.String)
     */
    public String getRootPath(CmsObject cms, String targetUri, String basePath) {
        String result = getSimpleRootPath(cms, targetUri, basePath);
        if (result == null) {
        	result = getDetailRootPath(cms, targetUri);
        }
        return result;

    }

    /**
     * Gets the root path without taking into account detail page links.<p>
     * 
     * @param cms - see the getRootPath() method
     * @param targetUri - see the getRootPath() method
     * @param basePath - see the getRootPath() method
     * @return - see the getRootPath() method
     */
    protected String getSimpleRootPath(CmsObject cms, String targetUri, String basePath) {

        if (cms == null) {
            // required by unit test cases
            return targetUri;
        }

        URI uri;
        String path;
        String suffix = "";

        // malformed uri
        try {
            uri = new URI(targetUri);
            path = uri.getPath();
            suffix = getSuffix(uri);
        } catch (Exception e) {
            if (LOG.isWarnEnabled()) {
                LOG.warn(Messages.get().getBundle().key(Messages.LOG_MALFORMED_URI_1, targetUri), e);
            }
            return null;
        }
        // opaque URI
        if (uri.isOpaque()) {
            return null;
        }

        CmsStaticExportManager exportManager = OpenCms.getStaticExportManager();
        if (exportManager.isValidRfsName(path)) {
            String originalSiteRoot = cms.getRequestContext().getSiteRoot();
            String vfsName = null;
            try {
                cms.getRequestContext().setSiteRoot("");
                vfsName = exportManager.getVfsName(cms, path);
                if (vfsName != null) {
                    return vfsName;
                }
            } finally {
                cms.getRequestContext().setSiteRoot(originalSiteRoot);
            }
        }

        // absolute URI (i.e. URI has a scheme component like http:// ...)
        if (uri.isAbsolute()) {
            CmsSiteMatcher matcher = new CmsSiteMatcher(targetUri);
            if (OpenCms.getSiteManager().isMatching(matcher)) {

                if (path.startsWith(OpenCms.getSystemInfo().getOpenCmsContext())) {
                    path = path.substring(OpenCms.getSystemInfo().getOpenCmsContext().length());
                }
                boolean isWorkplaceServer = OpenCms.getSiteManager().isWorkplaceRequest(matcher);
                if (isWorkplaceServer) {
                    String pathForCurrentSite = cms.getRequestContext().addSiteRoot(path);
                    String pathForMatchedSite = cms.getRequestContext().addSiteRoot(
                        OpenCms.getSiteManager().matchSite(matcher).getSiteRoot(),
                        path);
                    String siteRootFromPath = OpenCms.getSiteManager().getSiteRoot(path);
                    String originalSiteRoot = cms.getRequestContext().getSiteRoot();
                    String selectedPath = pathForCurrentSite;
                    if (siteRootFromPath != null) {
                        selectedPath = CmsStringUtil.joinPaths("/", path);
                    } else {
                        try {
                            cms.getRequestContext().setSiteRoot("");
                            // the path for the current site normally is preferred, but if it doesn't exist and the path for the matched site
                            // does exist, then use the path for the matched site 
                            if (!cms.existsResource(pathForCurrentSite, CmsResourceFilter.ALL)
                                && cms.existsResource(pathForMatchedSite, CmsResourceFilter.ALL)) {
                                selectedPath = pathForMatchedSite;
                            }
                        } finally {
                            cms.getRequestContext().setSiteRoot(originalSiteRoot);
                        }
                    }
                    return selectedPath + suffix;
                } else {
                    // add the site root of the matching site
                    return cms.getRequestContext().addSiteRoot(
                        OpenCms.getSiteManager().matchSite(matcher).getSiteRoot(),
                        path + suffix);
                }
            } else {
                return null;
            }
        }

        // relative URI (i.e. no scheme component, but filename can still start with "/") 
        String context = OpenCms.getSystemInfo().getOpenCmsContext();
        if ((context != null) && path.startsWith(context)) {
            // URI is starting with opencms context
            String siteRoot = null;
            if (basePath != null) {
                siteRoot = OpenCms.getSiteManager().getSiteRoot(basePath);
            }

            // cut context from path
            path = path.substring(context.length());

            if (siteRoot != null) {
                // special case: relative path contains a site root, i.e. we are in the root site                
                if (!path.startsWith(siteRoot)) {
                    // path does not already start with the site root, we have to add this path as site prefix
                    return cms.getRequestContext().addSiteRoot(siteRoot, path + suffix);
                } else {
                    // since path already contains the site root, we just leave it unchanged
                    return path + suffix;
                }
            } else {
                // site root is added with standard mechanism
                return cms.getRequestContext().addSiteRoot(path + suffix);
            }
        }

        // URI with relative path is relative to the given relativePath if available and in a site, 
        // otherwise invalid
        if (CmsStringUtil.isNotEmpty(path) && (path.charAt(0) != '/')) {
            if (basePath != null) {
                String absolutePath;
                int pos = path.indexOf("../../galleries/pics/");
                if (pos >= 0) {
                    // HACK: mixed up editor path to system gallery image folder
                    return CmsWorkplace.VFS_PATH_SYSTEM + path.substring(pos + 6) + suffix;
                }
                absolutePath = CmsLinkManager.getAbsoluteUri(path, cms.getRequestContext().addSiteRoot(basePath));
                if (OpenCms.getSiteManager().getSiteRoot(absolutePath) != null) {
                    return absolutePath + suffix;
                }
                // HACK: some editor components (e.g. HtmlArea) mix up the editor URL with the current request URL 
                absolutePath = CmsLinkManager.getAbsoluteUri(path, cms.getRequestContext().getSiteRoot()
                    + CmsWorkplace.VFS_PATH_EDITORS);
                if (OpenCms.getSiteManager().getSiteRoot(absolutePath) != null) {
                    return absolutePath + suffix;
                }
                // HACK: same as above, but XmlContent editor has one path element more
                absolutePath = CmsLinkManager.getAbsoluteUri(path, cms.getRequestContext().getSiteRoot()
                    + CmsWorkplace.VFS_PATH_EDITORS
                    + "xmlcontent/");
                if (OpenCms.getSiteManager().getSiteRoot(absolutePath) != null) {
                    return absolutePath + suffix;
                }
            }

            return null;
        }

        if (CmsStringUtil.isNotEmpty(path)) {
            if (OpenCms.getSiteManager().getSiteRoot(path) != null) {
                // path already seems to be a root path 
                return path + suffix;
            }
            // relative URI (= VFS path relative to currently selected site root)
            return cms.getRequestContext().addSiteRoot(path) + suffix;
        }

        // URI without path (typically local link)
        return suffix;
    }

    /**
     * Checks whether a link to a detail page should be secure.<p>
     * 
     * @param cms the current CMS context 
     * @param detailPage the detail page path 
     * @param detailContent the detail content resource 
     * @param targetSite the target site containing the detail page 
     * @param secureRequest true if the currently running request is secure 
     * 
     * @return true if the link should be a secure link 
     */
    protected boolean isDetailPageLinkSecure(
        CmsObject cms,
        String detailPage,
        CmsResource detailContent,
        CmsSite targetSite,
        boolean secureRequest) {

        boolean result = false;
        CmsStaticExportManager exportManager = OpenCms.getStaticExportManager();
        try {
            cms = OpenCms.initCmsObject(cms);
            if (targetSite.getSiteRoot() != null) {
                cms.getRequestContext().setSiteRoot(targetSite.getSiteRoot());
            }
            CmsResource defaultFile = cms.readDefaultFile(detailPage);
            if (defaultFile != null) {
                result = exportManager.isSecureLink(cms, defaultFile.getRootPath(), "", secureRequest);
            }
        } catch (Exception e) {
            LOG.error("Error while checking whether detail page link should be secure: " + e.getLocalizedMessage(), e);
        }
        return result;
    }

    /**
     * Gets the suffix (query + fragment) of the URI.<p>
     * 
     * @param uri the URI 
     * @return the suffix of the URI 
     */
    String getSuffix(URI uri) {

        String fragment = uri.getFragment();
        if (fragment != null) {
            fragment = "#" + fragment;
        } else {
            fragment = "";
        }

        String query = uri.getQuery();
        if (query != null) {
            query = "?" + query;
        } else {
            query = "";
        }
        return query.concat(fragment);
    }

    /**
     * Tries to interpret the given URI as a detail page URI and returns the detail content's root path if possible.<p>
     * 
     * If the given URI is not a detail URI, null will be returned.<p>
     * 
     * @param cms the CMS context to use 
     * @param result the detail root path, or null if the given uri is not a detail page URI
     *  
     * @return the detail content root path 
     */
    private String getDetailRootPath(CmsObject cms, String result) {

        if (result == null) {
            return null;
        }
        try {
            URI uri = new URI(result);
            String path = uri.getPath();
            if (CmsStringUtil.isEmptyOrWhitespaceOnly(path)) {
                return null;
            }
            String name = CmsFileUtil.removeTrailingSeparator(CmsResource.getName(path));
            CmsUUID detailId = OpenCms.getADEManager().getDetailIdCache(
                cms.getRequestContext().getCurrentProject().isOnlineProject()).getDetailId(name);
            if (detailId == null) {
                return null;
            }
            String origSiteRoot = cms.getRequestContext().getSiteRoot();
            try {
                cms.getRequestContext().setSiteRoot("");
                // real root paths have priority over detail contents 
                if (cms.existsResource(result)) {
                    return null;
                }
            } finally {
                cms.getRequestContext().setSiteRoot(origSiteRoot);
            }
            CmsResource detailResource = cms.readResource(detailId, CmsResourceFilter.ALL);
            return detailResource.getRootPath() + getSuffix(uri);
        } catch (Exception e) {
            if (LOG.isWarnEnabled()) {
                LOG.warn(e.getLocalizedMessage(), e);
            }
            return null;
        }
    }
}
