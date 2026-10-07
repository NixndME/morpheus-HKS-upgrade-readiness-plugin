package com.morpheuslab.hksupgrade

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HandlebarsRenderer

/** HKS Upgrade Readiness: checks whether an HKS cluster can be upgraded to the next Kubernetes version. */
class UpgradeReadinessPlugin extends Plugin {

    static final String PERMISSION = 'hks-upgrade-readiness'

    @Override
    String getCode() { 'hks-upgrade-readiness' }

    @Override
    void initialize() {
        setName('HKS Upgrade Readiness')
        setDescription('Checks whether an HKS cluster is ready for a Kubernetes upgrade')
        // a plugin with controllers must own its renderer (Morpheus 9.0.2), which also gives the nonce helper
        HandlebarsRenderer r = new HandlebarsRenderer('renderer', getClassLoader())
        r.registerAssetHelper(getName())
        r.registerNonceHelper(morpheus.getWebRequest())
        r.registerI18nHelper(this, morpheus)
        setRenderer(r)
        Permission p = Permission.build('HKS Upgrade Readiness', PERMISSION, [Permission.AccessType.none, Permission.AccessType.read])
        p.subCategory = 'HKS Upgrade Readiness'
        setPermissions([p])
        registerProvider(new ReadinessTabProvider(this, morpheus))
        controllers.add(new ReadinessController(this, morpheus))
    }

    @Override
    void onDestroy() { }   // nothing is ever installed in the clusters
}
